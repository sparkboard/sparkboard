# Clojure MCP Setup for Sparkboard

This project has been configured to use [clojure-mcp](https://github.com/bhauman/clojure-mcp) for AI-assisted Clojure development.

## Prerequisites

- **Clojure** 1.11 or later
- **Java JDK** 11 or later  
- **ripgrep** (recommended for enhanced search capabilities)
  - macOS: `brew install ripgrep`
  - Linux: `sudo apt-get install ripgrep` or `cargo install ripgrep`
  - Check installation: `rg --version`

## Setup Complete ✓

The following has been configured:

1. **Project deps.edn**: Added `:nrepl` alias for running nREPL server on port 7888
2. **Global ~/.clojure/deps.edn**: Added `:mcp` alias for clojure-mcp server
3. **Project configuration**: Created `.clojure-mcp/config.edn` with allowed directories
4. **Claude Desktop**: Updated config to include clojure-mcp server
5. **Claude Code**: Added `.mcp.json` for project-scoped MCP configuration

## Usage

### Starting the Development Environment

1. **Start nREPL server** (in project directory):
   ```bash
   clojure -M:nrepl
   ```

2. **Start MCP server** (in a separate terminal):
   ```bash
   clojure -X:mcp :port 7888
   ```

3. **Open Claude Desktop** and start a new conversation - clojure-mcp should be available

### Alternative: Use Claude Desktop Only

After restarting Claude Desktop, the clojure-mcp server should start automatically when you begin a new conversation.

### Using with Claude Code

The `.mcp.json` file has been added to the project root, making clojure-mcp available in Claude Code:

1. Open this project in Claude Code
2. You'll be prompted to approve the MCP server from `.mcp.json`
3. Start nREPL with `clojure -M:nrepl` before using
4. The MCP server will automatically connect to the running nREPL

You can also add clojure-mcp as a user-scoped server (available in all projects):
```bash
claude mcp add clojure-mcp -s user -- /bin/sh -c "export PATH=/opt/homebrew/bin:$PATH; exec clojure -X:mcp :port 7888"
```

## Features

- REPL-driven development with AI assistance
- Clojure-aware code editing
- Automatic parenthesis balancing
- Project context awareness
- Integration with your existing Sparkboard codebase

## Troubleshooting

If the MCP server doesn't connect:
1. Ensure nREPL is running on port 7888
2. Check that `clojure` command is available in PATH
3. Restart Claude Desktop after configuration changes
4. Check logs in Claude Desktop developer tools

## Project Structure

The Sparkboard project uses:
- Datalevin for database
- Shadow-cljs for ClojureScript compilation
- Ring/HTTP-kit for web server
- Firebase integration
- Various UI libraries (Reagent, etc.)

The MCP integration allows AI assistance with all these components.