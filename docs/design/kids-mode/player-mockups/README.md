# JellyScope Kids player — selected responsive layouts

This folder contains only the selected Kids player layouts for touch devices.
The images are layout references rather than literal artwork specifications.

## Selected files

- `selected-phone-portrait.png`
- `selected-phone-landscape.png`
- `selected-tablet-portrait.png`
- `selected-tablet-landscape.png`

## Responsive layout contract

### Video

- The active video is always a standard, undistorted 16:9 viewport.
- Content respects the top system-bar safe inset.
- In portrait, the video spans the full screen width with no horizontal outer
  padding.
- In landscape, the complete composition fills the screen width: the player is
  flush to the left edge and the fixed recommendation region is flush to the
  right edge.
- Playback controls remain overlaid inside the player and are never duplicated
  below it.

### Recommendations

- Recommendations are static page content, never a bottom sheet, drawer,
  floating overlay, or autoplay queue.
- Portrait uses horizontal image carousels below the player in the normal
  vertically scrolling page.
- Landscape uses a fixed right-side recommendation region. That region may
  scroll normally if its content exceeds the viewport, but it never expands,
  collapses, drags, or covers the player.
- Every recommendation uses a 16:9 image, an optional duration badge, and one
  single-line title directly below the image.
- Long titles ellipsize instead of wrapping.

### Visual hierarchy

- Video and recommendation artwork carry the hierarchy.
- Current-title, section, and recommendation text remains compact.
- The player does not need persistent app-name or Kids Mode branding.
- Do not add subscriptions, likes, comments, channel rows, ads, social actions,
  previous/next episode buttons, or implicit queue behavior.

Generated with OpenAI's built-in image-generation tool.
