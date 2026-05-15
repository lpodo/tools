# gh-pusher

A standalone HTML tool for pushing files to GitHub directly from your browser — no terminal, no IDE, no extensions required.

## How it works

Open the HTML file locally in your browser. It calls the GitHub Contents API directly, committing one or more files to a specified repository in a single click.

The GitHub token is saved in `localStorage` — enter it once, and it persists across sessions.

## Workflow

This tool is designed for use with AI-assisted development in [Claude](https://claude.ai):

1. Describe a task to Claude in chat
2. Discuss the implementation
3. Claude generates a ready-to-use pusher HTML file with the updated file contents embedded
4. Download the HTML, open in browser, click **Push to GitHub**

No zip archives. No manual uploads. No copy-paste.

## Setup

1. Create a GitHub Personal Access Token:
   **GitHub → Settings → Developer settings → Personal access tokens → Tokens (classic)**
   Required scope: `public_repo` (for public repositories) or `repo` (for private repositories)

2. Open `gh-pusher.html` in your browser

3. Enter your token and click **Save** — it will be remembered

## Configuration

At the top of the HTML file, Claude fills in the following block for each task:

```javascript
// ─── CONFIGURATION ───────────────────────────────────────────────────────
const REPO   = "owner/repo";          // GitHub repository
const BRANCH = "main";                // Target branch
const COMMIT_MESSAGE = "feat: ...";   // Commit message

const FILES = [
  {
    path: "src/index.js",
    content: `...full file content...`
  },
  {
    path: "styles/main.css",
    content: `...full file content...`
  }
];
```

Each entry in `FILES` is the **complete** new content of the file — not a diff.

## Features

- Pushes multiple files in one commit message
- Automatically fetches the current SHA before writing (required by GitHub API)
- Handles both new files and updates to existing files
- Per-file status indicators (pending / pushing / done / error)
- Editable commit message before pushing
- Token sanitization (strips invisible characters and whitespace)

## Security

- The token is stored in your browser's `localStorage` (local to your machine)
- It is never sent anywhere except directly to `api.github.com`
- No server, no proxy, no third-party services involved
