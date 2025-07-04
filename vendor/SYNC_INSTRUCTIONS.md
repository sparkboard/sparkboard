# Syncing Vendor Libraries Back to Original Repositories

This document explains how to extract changes made to vendor libraries and sync them back to their original repositories. Since we used Git's subtree merge strategy (not the `git subtree` command), we need to use alternative approaches.

## Background

The vendor libraries were imported using Git's built-in subtree merge strategy:

```bash
# How we imported each library (for reference)
git remote add <library>-tmp https://github.com/mhuebert/<library>.git
git fetch <library>-tmp
git merge -s ours --no-commit --allow-unrelated-histories <library>-tmp/main
git read-tree --prefix=vendor/<library>/ -u <library>-tmp/main
git commit -m "Add <library> library as vendor dependency with full history"
git remote remove <library>-tmp
```

## Methods to Sync Changes Back

### Method 1: Cherry-pick Specific Commits

Best for: When you have a few specific commits to sync back.

```bash
# 1. Create a working directory and clone the original repo
mkdir -p /tmp/sync-work && cd /tmp/sync-work
git clone https://github.com/mhuebert/<library>.git
cd <library>
git checkout -b sparkboard-changes

# 2. Add sparkboard as remote
git remote add sparkboard /Users/huebert/IdeaProjects/sparkboard
git fetch sparkboard

# 3. Find commits that touched the vendor library
# In sparkboard repo, run:
git log --oneline -- vendor/<library>/

# 4. Cherry-pick specific commits (adjust the path stripping)
# For each relevant commit:
git cherry-pick --no-commit <commit-hash>
# Manual step: you'll need to adjust paths from vendor/<library>/* to ./*

# 5. Push to original repo
git push origin sparkboard-changes
```

### Method 2: Patch-based Sync

Best for: When you have multiple commits and want to preserve commit messages.

```bash
# 1. In sparkboard repo, find the merge commit for the library
cd /Users/huebert/IdeaProjects/sparkboard
MERGE_COMMIT=$(git log --grep="Add <library> library as vendor dependency" --format="%H" -1)

# 2. Generate patches for all changes since the merge
mkdir -p /tmp/<library>-patches
git format-patch $MERGE_COMMIT..HEAD --output-directory=/tmp/<library>-patches -- vendor/<library>/

# 3. Clone and prepare the original repo
cd /tmp
git clone https://github.com/mhuebert/<library>.git
cd <library>
git checkout -b sparkboard-changes

# 4. Apply patches (stripping the vendor/<library>/ prefix)
for patch in /tmp/<library>-patches/*.patch; do
    # This strips 3 path components (vendor/<library>/)
    git apply --directory=. -p3 "$patch"
    
    # Extract commit message and commit
    SUBJECT=$(grep "^Subject: " "$patch" | sed 's/Subject: \[PATCH[^]]*\] //')
    git add -A
    git commit -m "$SUBJECT"
done

# 5. Push the branch
git push origin sparkboard-changes
```

### Method 3: Manual File Sync

Best for: Quick sync of current state without preserving individual commits.

```bash
# 1. Clone the original repository
cd /tmp
git clone https://github.com/mhuebert/<library>.git
cd <library>
git checkout -b sparkboard-sync

# 2. Sync files from sparkboard (preserving deletions)
rsync -av --delete \
    /Users/huebert/IdeaProjects/sparkboard/vendor/<library>/ \
    ./ \
    --exclude='.git'

# 3. Review changes
git status
git diff

# 4. Commit all changes
git add -A
git commit -m "Sync changes from sparkboard

These changes were made while <library> was vendored in the sparkboard project.
Source: https://github.com/sparkboard/sparkboard/tree/main/vendor/<library>"

# 5. Push to create PR
git push origin sparkboard-sync
```

### Method 4: Using git subtree (if installed)

If you install git-subtree, the process becomes simpler:

```bash
# Install git-subtree on macOS
brew install git-subtree

# Extract changes as a branch
cd /Users/huebert/IdeaProjects/sparkboard
git subtree split --prefix=vendor/<library> -b <library>-changes

# Push directly to the original repo
git push https://github.com/mhuebert/<library>.git <library>-changes:sparkboard-changes

# Clean up local branch
git branch -D <library>-changes
```

## Specific Examples for Our Libraries

### Syncing re-db changes:
```bash
# The merge commit for re-db is: 9049c7a
git format-patch 9049c7a..HEAD --output-directory=/tmp/re-db-patches -- vendor/re-db/
```

### Syncing yawn changes:
```bash
# The merge commit for yawn is: 9897c53
git format-patch 9897c53..HEAD --output-directory=/tmp/yawn-patches -- vendor/yawn/
```

### Syncing inside-out changes:
```bash
# The merge commit for inside-out is: 329941c
git format-patch 329941c..HEAD --output-directory=/tmp/inside-out-patches -- vendor/inside-out/
```

## Tips

1. **Test First**: Always test your sync in a temporary directory first
2. **Review Changes**: Carefully review what you're syncing back
3. **Communicate**: If the original repos are public, consider opening an issue first
4. **Keep History Clean**: Consider squashing related commits before syncing
5. **Document**: Add clear commit messages explaining the changes

## Future Imports

If you need to import additional libraries in the future, use the same subtree merge strategy documented in the main README.md.