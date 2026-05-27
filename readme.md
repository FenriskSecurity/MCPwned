# MCPwned

One-line summary : `Companion extension for pwning MCP servers`

Detailed description :

> MCPwned is a companion extension that enables pentesters to effectively test MCP servers. It recognizes MCP-like endpoints, provides a scanner and a tree-like display of capabilities, as well as a template request for each capability. It also provides quality of life features such as response extracting and session ID refresh. 

## Basic Usage

Whenever you encounter an MCP-like endpoint the extension will color it in gray. From there you can send the request to the extension with a right-click or a `Ctrl + M` shortcut. You can among others :

- Send a capability usage template to the repeater (also supports completion)
- Annotate/color capabilities to stay organized
- Refresh session ID from the repeater or the extension tab
- Export your findings in Markdown

Please find below the other features ;)

## Features (Roadmap)

### Scanning & Auditing

- [x] The extension provides a scanner for :
  - [x] Tools
  - [x] Prompts
  - [x] Resources
    - [x] + resource template
- [x] Debug mode configurable from settings
- [ ] Configurable logging of the request in the `logger` tab or at least some way to see the queries being made
- [x] pulls `session id` in server data based on the URL
- [x] Autodetects MCP-like exchanges in proxy

### Attacking

- [x] Enables to send a request to the `repeater`
  - [x] Attaches the session ID properly
  - [x] Provides a way of retrieving new session IDs automatically
- [x] Provides a way to test completion to leak valid resource URIs and argument values the server accepts
- [x] pulls `session id` in `repeater` based on the URL
- [x] Shows responses in a nice readable format

### QOL

- [x] Shortcuts :
  - [x] `Ctrl + M` → sends to extension
  - [x] `Ctrl + Shift + M` → move to extension page
- [x] display nicely
  - [x] show in tree form
  - [x] displays the tool/resource/prompt information
  - [x] combine/merge all data from the same URL and updates accordingly
  - [x] Show error in case of error at MCP connection level
  - [x] Delete entries

### Reporting and Organizing

- [x] Change server name
- [x] Set leaf color
- [x] Add notes
- [x] Export to Markdown

## Bugs to fix

- [x] Hangs on `HTTPStreambuilder`
- [x] Color bug in tree
- [x] Session ID being invalidated when SDK sends DELETE
- [x] `Scan with ...` Not working from `repeater` and other pane-based tabs
- [x] `Send to repeater` does not properly focus the new tab
- [x] Foreground color does not update properly on leaves leaving white on white sometimes
- [ ] Tree sometimes showing up weird when reloading project
- [ ] `\n` not handled properly in the right panel when used in description
- [ ] Server data display does not refresh upon session refresh

## BApp Store acceptance criteria

- [x] It performs a unique function.
- [x] It has a clear, descriptive name
- [x] It operates securely.
- [x] It includes all dependencies.
- [x] It uses threads to maintain responsiveness.
- [x] It unloads cleanly.
- [ ] It uses Burp networking.
  - [ ] Still uses `io.modelcontextprotocol.sdk:mcp` for probing due to no better solution for now
- [x] It supports offline working.
- [x] It can cope with large projects.
- [x] It provides a parent for GUI elements.
- [x] It uses the Montoya API artifact.
- [x] It uses Burp AI as the default AI provider