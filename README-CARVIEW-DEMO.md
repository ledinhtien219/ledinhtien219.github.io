# MyCarView Demo

Prototype Android app inspired by the CarView AA layout discussed in ChatGPT.

Current demo features:
- Mobile WebView with URL bar and YouTube mobile home
- Back / Forward / Home / Refresh / Settings toolbar
- HTML5 fullscreen video handling through WebChromeClient
- Settings screen styled like the reference screenshots
- Landscape Car Preview mode (phone-side preview only)
- Draggable floating speed bubble demo using Android overlay permission

Important: this branch is a phone-side prototype. It does not yet project arbitrary video or overlays onto Android Auto.

GitHub Actions on branch `carview-demo` builds `app-debug.apk` and uploads it as the artifact `MyCarViewDemo-debug-apk`.
