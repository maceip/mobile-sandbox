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

static int print_worktree(git_repository *repo, const char *name)
{
	git_worktree *wt = NULL;
	if (git_worktree_lookup(&wt, repo, name) != 0) {
		fprintf(stderr, "worktree: lookup failed for '%s': %s\n",
			name, git_error_last() ? git_error_last()->message : "?");
		return -1;
	}

	const char *path = git_worktree_path(wt);
	int valid = (git_worktree_validate(wt) == 0);

	printf("%-24s %s%s\n",
	       name,
	       path ? path : "<unknown>",
	       valid ? "" : "  (prunable)");

	git_worktree_free(wt);
	return 0;
}

static int cmd_list(git_repository *repo)
{
	git_strarray names = {0};
	int err = git_worktree_list(&names, repo);
	if (err < 0) {
		fprintf(stderr, "worktree list failed: %s\n",
			git_error_last() ? git_error_last()->message : "?");
		return err;
	}

	if (names.count == 0) {
		printf("(no worktrees)\n");
	} else {
		for (size_t i = 0; i < names.count; i++) {
			print_worktree(repo, names.strings[i]);
		}
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
	if (argc < 1) {
		fprintf(stderr, "usage: git worktree remove <name>\n");
		return -1;
	}

	const char *name = argv[0];
	git_worktree *wt = NULL;

	if (git_worktree_lookup(&wt, repo, name) != 0) {
		fprintf(stderr, "worktree remove: '%s' not found: %s\n",
			name, git_error_last() ? git_error_last()->message : "?");
		return -1;
	}

	git_worktree_prune_options opts = GIT_WORKTREE_PRUNE_OPTIONS_INIT;
	opts.flags = GIT_WORKTREE_PRUNE_VALID | GIT_WORKTREE_PRUNE_WORKING_TREE;

	int err = git_worktree_prune(wt, &opts);
	if (err < 0) {
		fprintf(stderr, "worktree remove failed: %s\n",
			git_error_last() ? git_error_last()->message : "?");
	} else {
		printf("Removed worktree '%s'\n", name);
	}

	git_worktree_free(wt);
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
