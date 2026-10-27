# Preserved version and experiment

The source version captured for this experiment is:

- Commit: `e49775e583b54355a4915ca2e7dcc09fb8a8186e` (`Pilot-Tablet`).
- Baseline branch: `baseline/alia-pilot-2026-10-26`.
- Baseline tag: `alia-pilot-baseline-2026-10-26`.
- Experiment branch: `experiment/soar-energy-braking-memory`.
- GitHub repository: https://github.com/saminyasareram444/CCAT

## Local folders

- Existing / baseline checkout: `C:\Users\syasar\Documents\Study\Fall2026\CCAT\CCAT`.
- Research checkout: `C:\Users\syasar\Documents\Study\Fall2026\CCAT\CCAT-soar-research`.
- Backup directory: `C:\Users\syasar\Documents\Study\Fall2026\CCAT\CCAT-versions`.

Both checkouts use Git worktrees: files and branches are separate, but Git history is shared. The experiment's `.git` file points to metadata in the original repository. Keep the original repository in place, or use `git worktree move` if relocating a checkout. Copying only the research folder is not a complete Git backup.

The backup directory contains an independent source ZIP and a Git bundle. The ZIP captures tracked files at the baseline commit, including the existing launcher/source/test files. It excludes ignored build caches, flight logs and mission files. The original logs/missions remain in the original folder, and a research data snapshot is also stored under ignored `research/local/` in the research checkout.

The bundle preserves baseline history and can be cloned into a new directory with Git. The `snapshot-manifest.json` records snapshot hashes and the source commit. The research data manifest records copied file hashes separately.

## Switching versions

Close the active CCAT application, then open the launcher from the desired folder. You do not need to switch branches in the original folder to run the experiment copy.

Do not launch both controllers together against the same simulator. The research configuration begins in shadow / recommendation mode; no new cognitive controller has been implemented yet.

## GitHub history

The existing `main` branch is not rewritten or merged by this setup. Its local and remote histories already differ. The named baseline branch preserves the exact local version independently of that difference, and the experiment starts from that baseline. Future changes should be committed and pushed from the research checkout to its experiment branch.
