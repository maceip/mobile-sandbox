#include <Python.h>
#include <git2.h>

#include <memory>
#include <string>
#include <vector>

namespace {

struct RepositoryDeleter {
  void operator()(git_repository* repo) const {
    if (repo != nullptr) {
      git_repository_free(repo);
    }
  }
};

struct IndexDeleter {
  void operator()(git_index* index) const {
    if (index != nullptr) {
      git_index_free(index);
    }
  }
};

struct TreeDeleter {
  void operator()(git_tree* tree) const {
    if (tree != nullptr) {
      git_tree_free(tree);
    }
  }
};

struct CommitDeleter {
  void operator()(git_commit* commit) const {
    if (commit != nullptr) {
      git_commit_free(commit);
    }
  }
};

struct SignatureDeleter {
  void operator()(git_signature* signature) const {
    if (signature != nullptr) {
      git_signature_free(signature);
    }
  }
};

struct ReferenceDeleter {
  void operator()(git_reference* reference) const {
    if (reference != nullptr) {
      git_reference_free(reference);
    }
  }
};

struct ObjectDeleter {
  void operator()(git_object* object) const {
    if (object != nullptr) {
      git_object_free(object);
    }
  }
};

struct WorktreeDeleter {
  void operator()(git_worktree* worktree) const {
    if (worktree != nullptr) {
      git_worktree_free(worktree);
    }
  }
};

using RepositoryPtr = std::unique_ptr<git_repository, RepositoryDeleter>;
using IndexPtr = std::unique_ptr<git_index, IndexDeleter>;
using TreePtr = std::unique_ptr<git_tree, TreeDeleter>;
using CommitPtr = std::unique_ptr<git_commit, CommitDeleter>;
using SignaturePtr = std::unique_ptr<git_signature, SignatureDeleter>;
using ReferencePtr = std::unique_ptr<git_reference, ReferenceDeleter>;
using ObjectPtr = std::unique_ptr<git_object, ObjectDeleter>;
using WorktreePtr = std::unique_ptr<git_worktree, WorktreeDeleter>;

PyObject* GitError(const char* step, int error_code) {
  const git_error* error = git_error_last();
  std::string message(step);
  message += " failed";
  if (error != nullptr && error->message != nullptr) {
    message += ": ";
    message += error->message;
  } else {
    message += ": libgit2 error ";
    message += std::to_string(error_code);
  }
  PyErr_SetString(PyExc_RuntimeError, message.c_str());
  return nullptr;
}

RepositoryPtr OpenRepository(const char* path) {
  git_repository* repo = nullptr;
  if (git_repository_open_ext(&repo, path, 0, nullptr) != 0) {
    return RepositoryPtr(nullptr);
  }
  return RepositoryPtr(repo);
}

bool StartsWith(const char* value, const char* prefix) {
  return value != nullptr && prefix != nullptr &&
         std::string(value).rfind(prefix, 0) == 0;
}

char StatusCode(unsigned int status) {
  if ((status & GIT_STATUS_WT_NEW) != 0) return '?';
  if ((status & GIT_STATUS_INDEX_NEW) != 0) return 'A';
  if ((status & GIT_STATUS_INDEX_MODIFIED) != 0) return 'M';
  if ((status & GIT_STATUS_WT_MODIFIED) != 0) return 'M';
  if ((status & GIT_STATUS_INDEX_DELETED) != 0) return 'D';
  if ((status & GIT_STATUS_WT_DELETED) != 0) return 'D';
  if ((status & GIT_STATUS_INDEX_RENAMED) != 0) return 'R';
  if ((status & GIT_STATUS_INDEX_TYPECHANGE) != 0) return 'T';
  if ((status & GIT_STATUS_CONFLICTED) != 0) return 'U';
  return ' ';
}

int CollectStatus(const char* path, std::vector<std::string>* lines) {
  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    return GIT_ENOTFOUND;
  }

  git_status_options options = {};
  options.version = GIT_STATUS_OPTIONS_VERSION;
  options.show = GIT_STATUS_SHOW_INDEX_AND_WORKDIR;
  options.flags = GIT_STATUS_OPT_INCLUDE_UNTRACKED |
                  GIT_STATUS_OPT_RECURSE_UNTRACKED_DIRS |
                  GIT_STATUS_OPT_RENAMES_HEAD_TO_INDEX;

  git_status_list* status_list = nullptr;
  int error = git_status_list_new(&status_list, repo.get(), &options);
  if (error != 0) {
    return error;
  }

  const size_t count = git_status_list_entrycount(status_list);
  for (size_t index = 0; index < count; ++index) {
    const git_status_entry* entry = git_status_byindex(status_list, index);
    if (entry == nullptr) {
      continue;
    }
    const char* path_text = nullptr;
    if (entry->head_to_index != nullptr &&
        entry->head_to_index->new_file.path != nullptr) {
      path_text = entry->head_to_index->new_file.path;
    } else if (entry->index_to_workdir != nullptr &&
               entry->index_to_workdir->new_file.path != nullptr) {
      path_text = entry->index_to_workdir->new_file.path;
    }
    if (path_text == nullptr) {
      continue;
    }
    std::string line;
    line.push_back(StatusCode(entry->status));
    line.push_back(' ');
    line += path_text;
    lines->push_back(line);
  }

  git_status_list_free(status_list);
  return 0;
}

PyObject* PyGitVersion(PyObject*, PyObject*) {
  int major = 0;
  int minor = 0;
  int rev = 0;
  git_libgit2_version(&major, &minor, &rev);
  return PyUnicode_FromFormat("%d.%d.%d", major, minor, rev);
}

PyObject* PyGitInit(PyObject*, PyObject* args) {
  const char* path = nullptr;
  if (!PyArg_ParseTuple(args, "s", &path)) {
    return nullptr;
  }

  git_repository* repo = nullptr;
  const int error = git_repository_init(&repo, path, 0);
  if (error != 0) {
    return GitError("git_repository_init", error);
  }
  git_repository_free(repo);
  Py_RETURN_NONE;
}

PyObject* PyGitStatusShort(PyObject*, PyObject* args) {
  const char* path = nullptr;
  if (!PyArg_ParseTuple(args, "s", &path)) {
    return nullptr;
  }

  std::vector<std::string> lines;
  const int error = CollectStatus(path, &lines);
  if (error == GIT_ENOTFOUND) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }
  if (error != 0) {
    return GitError("git_status_list_new", error);
  }

  std::string output;
  for (const std::string& line : lines) {
    output += line;
    output.push_back('\n');
  }
  return PyUnicode_FromStringAndSize(output.c_str(), output.size());
}

PyObject* PyGitClone(PyObject*, PyObject* args) {
  const char* source = nullptr;
  const char* path = nullptr;
  if (!PyArg_ParseTuple(args, "ss", &source, &path)) {
    return nullptr;
  }

  git_clone_options options = {};
  git_clone_options_init(&options, GIT_CLONE_OPTIONS_VERSION);
  options.checkout_opts.checkout_strategy = GIT_CHECKOUT_SAFE;

  git_repository* raw_repo = nullptr;
  const int error = git_clone(&raw_repo, source, path, &options);
  if (error != 0) {
    return GitError("git_clone", error);
  }
  git_repository_free(raw_repo);
  Py_RETURN_NONE;
}

PyObject* PyGitAddAll(PyObject*, PyObject* args) {
  const char* path = nullptr;
  if (!PyArg_ParseTuple(args, "s", &path)) {
    return nullptr;
  }

  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }

  git_index* raw_index = nullptr;
  int error = git_repository_index(&raw_index, repo.get());
  if (error != 0) {
    return GitError("git_repository_index", error);
  }
  IndexPtr index(raw_index);

  git_strarray pathspec = {nullptr, 0};
  error = git_index_add_all(index.get(), &pathspec, GIT_INDEX_ADD_DEFAULT,
                            nullptr, nullptr);
  if (error != 0) {
    return GitError("git_index_add_all", error);
  }

  error = git_index_write(index.get());
  if (error != 0) {
    return GitError("git_index_write", error);
  }

  Py_RETURN_NONE;
}

PyObject* PyGitCommitAll(PyObject*, PyObject* args) {
  const char* path = nullptr;
  const char* message = nullptr;
  const char* author_name = nullptr;
  const char* author_email = nullptr;
  if (!PyArg_ParseTuple(args, "ssss", &path, &message, &author_name,
                        &author_email)) {
    return nullptr;
  }

  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }

  git_index* raw_index = nullptr;
  int error = git_repository_index(&raw_index, repo.get());
  if (error != 0) {
    return GitError("git_repository_index", error);
  }
  IndexPtr index(raw_index);

  error = git_index_add_all(index.get(), nullptr, GIT_INDEX_ADD_DEFAULT,
                            nullptr, nullptr);
  if (error != 0) {
    return GitError("git_index_add_all", error);
  }
  error = git_index_write(index.get());
  if (error != 0) {
    return GitError("git_index_write", error);
  }

  git_oid tree_id;
  error = git_index_write_tree(&tree_id, index.get());
  if (error != 0) {
    return GitError("git_index_write_tree", error);
  }

  git_tree* raw_tree = nullptr;
  error = git_tree_lookup(&raw_tree, repo.get(), &tree_id);
  if (error != 0) {
    return GitError("git_tree_lookup", error);
  }
  TreePtr tree(raw_tree);

  git_signature* raw_signature = nullptr;
  error = git_signature_now(&raw_signature, author_name, author_email);
  if (error != 0) {
    return GitError("git_signature_now", error);
  }
  SignaturePtr signature(raw_signature);

  git_oid parent_id;
  git_commit* raw_parent = nullptr;
  const git_commit* parents[1] = {};
  size_t parent_count = 0;
  if (git_reference_name_to_id(&parent_id, repo.get(), "HEAD") == 0) {
    error = git_commit_lookup(&raw_parent, repo.get(), &parent_id);
    if (error != 0) {
      return GitError("git_commit_lookup", error);
    }
    parents[0] = raw_parent;
    parent_count = 1;
  }
  CommitPtr parent(raw_parent);

  git_oid commit_id;
  error = git_commit_create(&commit_id, repo.get(), "HEAD", signature.get(),
                            signature.get(), nullptr, message, tree.get(),
                            parent_count, parents);
  if (error != 0) {
    return GitError("git_commit_create", error);
  }

  char commit_hex[GIT_OID_HEXSZ + 1] = {};
  git_oid_tostr(commit_hex, sizeof(commit_hex), &commit_id);
  return PyUnicode_FromString(commit_hex);
}

PyObject* PyGitLog(PyObject*, PyObject* args) {
  const char* path = nullptr;
  int max_count = 20;
  if (!PyArg_ParseTuple(args, "s|i", &path, &max_count)) {
    return nullptr;
  }

  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }

  git_revwalk* walker = nullptr;
  int error = git_revwalk_new(&walker, repo.get());
  if (error != 0) {
    return GitError("git_revwalk_new", error);
  }
  git_revwalk_sorting(walker, GIT_SORT_TIME);
  error = git_revwalk_push_head(walker);
  if (error != 0) {
    git_revwalk_free(walker);
    return GitError("git_revwalk_push_head", error);
  }

  std::string output;
  git_oid oid;
  int seen = 0;
  while (seen < max_count && git_revwalk_next(&oid, walker) == 0) {
    git_commit* raw_commit = nullptr;
    error = git_commit_lookup(&raw_commit, repo.get(), &oid);
    if (error != 0) {
      git_revwalk_free(walker);
      return GitError("git_commit_lookup", error);
    }
    CommitPtr commit(raw_commit);

    char oid_hex[GIT_OID_HEXSZ + 1] = {};
    git_oid_tostr(oid_hex, sizeof(oid_hex), &oid);
    output.append(oid_hex, 7);
    output.push_back(' ');
    const char* summary = git_commit_summary(commit.get());
    output += summary != nullptr ? summary : "(no message)";
    output.push_back('\n');
    ++seen;
  }

  git_revwalk_free(walker);
  return PyUnicode_FromStringAndSize(output.c_str(), output.size());
}

PyObject* PyGitCheckout(PyObject*, PyObject* args) {
  const char* path = nullptr;
  const char* target = nullptr;
  if (!PyArg_ParseTuple(args, "ss", &path, &target)) {
    return nullptr;
  }

  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }

  git_reference* raw_reference = nullptr;
  git_object* raw_object = nullptr;
  int error = git_revparse_ext(&raw_object, &raw_reference, repo.get(), target);
  if (error != 0) {
    return GitError("git_revparse_ext", error);
  }
  ObjectPtr object(raw_object);
  ReferencePtr reference(raw_reference);

  git_checkout_options options = {};
  git_checkout_options_init(&options, GIT_CHECKOUT_OPTIONS_VERSION);
  options.checkout_strategy = GIT_CHECKOUT_SAFE;
  error = git_checkout_tree(repo.get(), object.get(), &options);
  if (error != 0) {
    return GitError("git_checkout_tree", error);
  }

  if (reference != nullptr) {
    const char* ref_name = git_reference_name(reference.get());
    if (StartsWith(ref_name, "refs/heads/")) {
      error = git_repository_set_head(repo.get(), ref_name);
      if (error != 0) {
        return GitError("git_repository_set_head", error);
      }
    } else {
      error = git_repository_set_head_detached(repo.get(), git_object_id(object.get()));
      if (error != 0) {
        return GitError("git_repository_set_head_detached", error);
      }
    }
  } else {
    error = git_repository_set_head_detached(repo.get(), git_object_id(object.get()));
    if (error != 0) {
      return GitError("git_repository_set_head_detached", error);
    }
  }

  Py_RETURN_NONE;
}

PyObject* PyGitWorktreeList(PyObject*, PyObject* args) {
  const char* path = nullptr;
  if (!PyArg_ParseTuple(args, "s", &path)) {
    return nullptr;
  }

  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }

  git_strarray names = {};
  int error = git_worktree_list(&names, repo.get());
  if (error != 0) {
    return GitError("git_worktree_list", error);
  }

  std::string output;
  for (size_t index = 0; index < names.count; ++index) {
    git_worktree* raw_worktree = nullptr;
    error = git_worktree_lookup(&raw_worktree, repo.get(), names.strings[index]);
    if (error != 0) {
      git_strarray_dispose(&names);
      return GitError("git_worktree_lookup", error);
    }
    WorktreePtr worktree(raw_worktree);
    output += git_worktree_name(worktree.get());
    output.push_back('\t');
    output += git_worktree_path(worktree.get());
    output.push_back('\n');
  }
  git_strarray_dispose(&names);
  return PyUnicode_FromStringAndSize(output.c_str(), output.size());
}

PyObject* PyGitWorktreeAdd(PyObject*, PyObject* args) {
  const char* path = nullptr;
  const char* name = nullptr;
  const char* worktree_path = nullptr;
  const char* reference_text = nullptr;
  if (!PyArg_ParseTuple(args, "sss|z", &path, &name, &worktree_path, &reference_text)) {
    return nullptr;
  }

  RepositoryPtr repo = OpenRepository(path);
  if (repo == nullptr) {
    PyErr_SetString(PyExc_FileNotFoundError, "git repository not found");
    return nullptr;
  }

  git_worktree_add_options options = {};
  git_worktree_add_options_init(&options, GIT_WORKTREE_ADD_OPTIONS_VERSION);
  options.checkout_options.checkout_strategy = GIT_CHECKOUT_SAFE;

  git_reference* raw_reference = nullptr;
  ReferencePtr reference;
  if (reference_text != nullptr && reference_text[0] != '\0') {
    int error = git_reference_dwim(&raw_reference, repo.get(), reference_text);
    if (error != 0) {
      return GitError("git_reference_dwim", error);
    }
    reference.reset(raw_reference);
    options.ref = reference.get();
  }

  git_worktree* raw_worktree = nullptr;
  const int error = git_worktree_add(&raw_worktree, repo.get(), name, worktree_path, &options);
  if (error != 0) {
    return GitError("git_worktree_add", error);
  }
  git_worktree_free(raw_worktree);
  Py_RETURN_NONE;
}

PyMethodDef kCoryNativeMethods[] = {
    {"git_version", PyGitVersion, METH_NOARGS, nullptr},
    {"git_clone", PyGitClone, METH_VARARGS, nullptr},
    {"git_init", PyGitInit, METH_VARARGS, nullptr},
    {"git_status_short", PyGitStatusShort, METH_VARARGS, nullptr},
    {"git_add_all", PyGitAddAll, METH_VARARGS, nullptr},
    {"git_commit_all", PyGitCommitAll, METH_VARARGS, nullptr},
    {"git_checkout", PyGitCheckout, METH_VARARGS, nullptr},
    {"git_log", PyGitLog, METH_VARARGS, nullptr},
    {"git_worktree_list", PyGitWorktreeList, METH_VARARGS, nullptr},
    {"git_worktree_add", PyGitWorktreeAdd, METH_VARARGS, nullptr},
    {nullptr, nullptr, 0, nullptr},
};

PyModuleDef kCoryNativeModule = {
    PyModuleDef_HEAD_INIT,
    "cory_native",
    nullptr,
    -1,
    kCoryNativeMethods,
    nullptr,
    nullptr,
    nullptr,
    nullptr,
};

}  // namespace

extern "C" PyObject* PyInit_cory_native(void) {
  return PyModule_Create(&kCoryNativeModule);
}
