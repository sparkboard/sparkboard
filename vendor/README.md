# Vendor Dependencies

This directory contains vendored dependencies that are maintained as part of the sparkboard project. These libraries were originally external repositories but have been integrated using git subtree merge to preserve their complete git history.

## Current Vendor Libraries

- **inside-out** - A ClojureScript forms library
- **yawn** - Yet Another Web Notebook (reactive coding environment)
- **re-db** - Fast, reactive client-side triple-store for ClojureScript

## Why Vendor These Libraries?

These libraries are authored by the same developer as sparkboard and are not actively maintained in their separate repositories. By vendoring them:
- We can evolve them alongside sparkboard's needs
- Development is simplified with a single repository
- Complete git history is preserved for each library
- Changes can still be extracted and pushed back to original repos if needed

## Managing Vendor Libraries

### Pulling Updates from Upstream

To pull updates from the original repository (if any):

```bash
# Add the remote temporarily
git remote add <library>-upstream https://github.com/mhuebert/<library>.git

# Fetch and merge changes
git fetch <library>-upstream
git merge -X subtree=vendor/<library>/ <library>-upstream/main

# Remove the temporary remote
git remote remove <library>-upstream
```

### Pushing Changes Back to Upstream

To extract changes and push them back to the original repository:

```bash
# Create a branch with just the subtree changes
git subtree split --prefix=vendor/<library> -b <library>-changes

# Add the upstream remote
git remote add <library>-upstream https://github.com/mhuebert/<library>.git

# Push the branch
git push <library>-upstream <library>-changes:feature-branch-name

# Clean up
git branch -D <library>-changes
git remote remove <library>-upstream
```

## How These Were Added

Each library was added using git's subtree merge strategy to preserve complete history:

```bash
# Add remote and fetch
git remote add <library>-tmp https://github.com/mhuebert/<library>.git
git fetch <library>-tmp

# Merge with subtree strategy
git merge -s ours --no-commit --allow-unrelated-histories <library>-tmp/main
git read-tree --prefix=vendor/<library>/ -u <library>-tmp/main
git commit -m "Add <library> as vendor dependency with full history"

# Clean up
git remote remove <library>-tmp
```

## Development

These libraries are now part of the sparkboard project. You can:
- Edit them directly in the vendor directory
- Run their tests as part of sparkboard's test suite
- Use them via `:local/root` dependencies in deps.edn

The deps.edn file has been updated to reference these local paths instead of git coordinates.