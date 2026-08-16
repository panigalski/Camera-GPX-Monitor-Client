# Output Folder Full Path — Client 1.10.26

The Main Camera App Monitor now renders `Output Folder` as a dedicated multi-line field.

- The label is on the first line.
- The exact full path is shown beneath it.
- The `TextView` is not single-line, has no ellipsis, does not horizontally scroll, and has no practical line limit.
- Android simple line breaking is enabled with hyphenation disabled.
- The text is selectable, and the accessibility description retains the exact raw folder path.

No Main App or API change is required; `Dashboard.outputFolder` already contains the complete value.
