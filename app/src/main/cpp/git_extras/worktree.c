/*
 * worktree.c — libgit2-examples-style implementation of `git worktree`
 *
 * libgit2 exposes a full worktree C API (git_worktree_add / _list /
 * _lookup / _prune / _is_prunable / _validate) but its examples/ dir
 * never shipped a worktree.c. This file fills that gap so our compiled
 * git binary can handle:
 *
 *   git worktree list
 *   git worktree add <path> [<name>]
 *   git worktree remove <name>
 *   git worktree prune
 *
 * Registered in lg2.c's dispatcher table. Compiled alongside the
 * upstream examples by app/src/main/cpp/CMakeLists.txt.
 */
#include "common.h"
#include <git2/worktree.h>

static void format_worktree_line(git_repository *wt_repo, const char *path)
{
	char short_sha[8] = "0000000";
	char branch[256] = "[unborn]";

	git_reference *head_ref = NULL;
	if (git_repository_head(&head_ref, wt_repo) == 0) {
		git_reference *resolved = NULL;
		if (git_reference_resolve(&resolved, head_ref) == 0) {
			const git_oid *oid = git_reference_target(resolved);
			if (oid) {
				char full[GIT_OID_HEXSZ + 1];
				git_oid_tostr(full, sizeof(full), oid);
				memcpy(short_sha, full, 7);
				short_sha[7] = '\0';
			}
			git_reference_free(resolved);
		}
		const char *shorthand = git_reference_shorthand(head_ref);
		if (shorthand) {
			snprintf(branch, sizeof(branch), "[%s]", shorthand);
		}
		git_reference_free(head_ref);
	} else if (head_ref) {
		git_reference_free(head_ref);
	}

	printf("%-40s %s %s\n", path, short_sha, branch);
}

static int print_worktree(git_repository *repo, const char *name)
{
	git_worktree *wt = NULL;
	git_repository *wt_repo = NULL;

	if (git_worktree_lookup(&wt, repo, name) != 0) {
		fprintf(stderr, "worktree: lookup failed for '%s': %s\n",
			name, git_error_last() ? git_error_last()->message : "?");
		return -1;
	}

	const char *path = git_worktree_path(wt);
	if (!path) {
		git_worktree_free(wt);
		return -1;
	}

	int valid = (git_worktree_validate(wt) == 0);

	if (valid && git_repository_open_from_worktree(&wt_repo, wt) == 0) {
		format_worktree_line(wt_repo, path);
		git_repository_free(wt_repo);
	} else {
		printf("%-40s %s %s\n", path, "0000000", "(prunable)");
	}

	git_worktree_free(wt);
	return 0;
}

static int cmd_list(git_repository *repo)
{
	/* Print main worktree first — same ordering as real git. */
	const char *main_workdir = git_repository_workdir(repo);
	if (main_workdir) {
		char path_clean[4096];
		snprintf(path_clean, sizeof(path_clean), "%s", main_workdir);
		size_t len = strlen(path_clean);
		if (len > 0 && path_clean[len - 1] == '/') {
			path_clean[len - 1] = '\0';
		}
		format_worktree_line(repo, path_clean);
	}

	git_strarray names = {0};
	if (git_worktree_list(&names, repo) < 0) {
		fprintf(stderr, "worktree list failed: %s\n",
			git_error_last() ? git_error_last()->message : "?");
		return -1;
	}

	for (size_t i = 0; i < names.count; i++) {
		print_worktree(repo, names.strings[i]);
	}

	git_strarray_dispose(&names);
	return 0;
}

static const char *derive_name(const char *path)
{
	const char *slash = strrchr(path, '/');
	return (slash && slash[1]) ? slash + 1 : path;
}

static int cmd_add(git_repository *repo, int argc, char **argv)
{
	if (argc < 1) {
		fprintf(stderr, "usage: git worktree add <path> [<name>]\n");
		return -1;
	}

	const char *path = argv[0];
	const char *name = (argc >= 2) ? argv[1] : derive_name(path);

	git_worktree_add_options opts = GIT_WORKTREE_ADD_OPTIONS_INIT;
	git_worktree *wt = NULL;

	int err = git_worktree_add(&wt, repo, name, path, &opts);
	if (err < 0) {
		fprintf(stderr, "worktree add failed: %s\n",
			git_error_last() ? git_error_last()->message : "?");
		return err;
	}

	printf("Created worktree '%s' at %s\n", name, path);
	git_worktree_free(wt);
	return 0;
}

static int cmd_remove(git_repository *repo, int argc, char **argv)
{
	int force = 0;
	const char *name = NULL;
	git_worktree *wt = NULL;
	git_repository *wt_repo = NULL;
	git_status_list *status = NULL;
	git_status_options stat_opts = GIT_STATUS_OPTIONS_INIT;
	git_worktree_prune_options prune_opts = GIT_WORKTREE_PRUNE_OPTIONS_INIT;
	int err = -1;
	size_t entries;

	for (int i = 0; i < argc; i++) {
		if (strcmp(argv[i], "--force") == 0 || strcmp(argv[i], "-f") == 0) {
			force = 1;
		} else if (argv[i][0] == '-') {
			fprintf(stderr, "worktree remove: unknown flag '%s'\n", argv[i]);
			return -1;
		} else if (name == NULL) {
			name = argv[i];
		} else {
			fprintf(stderr, "worktree remove: too many arguments\n");
			return -1;
		}
	}

	if (name == NULL) {
		fprintf(stderr, "usage: git worktree remove [--force] <name>\n");
		return -1;
	}

	if (git_worktree_lookup(&wt, repo, name) != 0) {
		fprintf(stderr, "worktree remove: '%s' not found: %s\n",
			name, git_error_last() ? git_error_last()->message : "?");
		return -1;
	}

	/* Refuse to delete a worktree with uncommitted modifications or
	 * untracked files unless --force is given. Real git's `worktree
	 * remove` does the same check; without it we'd silently nuke
	 * user work.
	 *
	 * We open the worktree as its own git_repository, run a status
	 * scan over the index + workdir, and bail if any entries come
	 * back. We deliberately don't include ignored files (build
	 * artifacts etc.) — that would be too aggressive and doesn't
	 * match real git's behavior.
	 */
	if (!force) {
		if (git_repository_open_from_worktree(&wt_repo, wt) < 0) {
			fprintf(stderr, "worktree remove: cannot open worktree '%s' as a repo: %s\n",
				name,
				git_error_last() ? git_error_last()->message : "?");
			goto cleanup;
		}

		stat_opts.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR;
		stat_opts.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED |
				  GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS;

		if (git_status_list_new(&status, wt_repo, &stat_opts) < 0) {
			fprintf(stderr, "worktree remove: cannot read status: %s\n",
				git_error_last() ? git_error_last()->message : "?");
			goto cleanup;
		}

		entries = git_status_list_entrycount(status);
		if (entries > 0) {
			fprintf(stderr,
				"worktree remove: '%s' has %" PRIuZ " modified or untracked file(s).\n"
				"To remove anyway, run: git worktree remove --force %s\n",
				name, entries, name);
			err = -1;
			goto cleanup;
		}
	}

	/* GIT_WORKTREE_PRUNE_VALID overrides the default `is_prunable`
	 * gate (a valid worktree is normally not prunable). We've
	 * already done our own dirty-state check above (or the user
	 * passed --force), so it's safe to force the prune here.
	 * GIT_WORKTREE_PRUNE_WORKING_TREE deletes the on-disk dir.
	 */
	prune_opts.flags = GIT_WORKTREE_PRUNE_VALID | GIT_WORKTREE_PRUNE_WORKING_TREE;
	err = git_worktree_prune(wt, &prune_opts);
	if (err < 0) {
		fprintf(stderr, "worktree remove failed: %s\n",
			git_error_last() ? git_error_last()->message : "?");
	} else {
		printf("Removed worktree '%s'\n", name);
	}

cleanup:
	if (status) git_status_list_free(status);
	if (wt_repo) git_repository_free(wt_repo);
	if (wt) git_worktree_free(wt);
	return err;
}

static int cmd_prune(git_repository *repo)
{
	git_strarray names = {0};
	int err = git_worktree_list(&names, repo);
	if (err < 0) {
		fprintf(stderr, "worktree list failed: %s\n",
			git_error_last() ? git_error_last()->message : "?");
		return err;
	}

	int pruned = 0;
	for (size_t i = 0; i < names.count; i++) {
		git_worktree *wt = NULL;
		if (git_worktree_lookup(&wt, repo, names.strings[i]) != 0) {
			continue;
		}

		git_worktree_prune_options opts = GIT_WORKTREE_PRUNE_OPTIONS_INIT;
		if (git_worktree_is_prunable(wt, &opts)) {
			if (git_worktree_prune(wt, &opts) == 0) {
				printf("pruned '%s'\n", names.strings[i]);
				pruned++;
			}
		}
		git_worktree_free(wt);
	}

	if (pruned == 0) {
		printf("(nothing to prune)\n");
	}

	git_strarray_dispose(&names);
	return 0;
}

static void usage(void)
{
	fprintf(stderr,
		"usage: git worktree <command> [<args>]\n"
		"\n"
		"commands:\n"
		"  list                     list worktrees attached to this repo\n"
		"  add <path> [<name>]      add a new worktree at <path>\n"
		"  remove <name>            remove (prune + delete) a worktree\n"
		"  prune                    prune stale worktree metadata\n");
}

int lg2_worktree(git_repository *repo, int argc, char **argv)
{
	/* argv[0] is the subcommand name ("worktree"); skip it. */
	if (argc < 2) {
		usage();
		return -1;
	}

	const char *sub = argv[1];
	int sub_argc = argc - 2;
	char **sub_argv = argv + 2;

	if (strcmp(sub, "list") == 0) {
		return cmd_list(repo);
	}
	if (strcmp(sub, "add") == 0) {
		return cmd_add(repo, sub_argc, sub_argv);
	}
	if (strcmp(sub, "remove") == 0) {
		return cmd_remove(repo, sub_argc, sub_argv);
	}
	if (strcmp(sub, "prune") == 0) {
		return cmd_prune(repo);
	}

	fprintf(stderr, "worktree: unknown subcommand '%s'\n\n", sub);
	usage();
	return -1;
}
