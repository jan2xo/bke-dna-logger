(() => {
  "use strict";

  // Browser-isolation diagnostic: intentionally leave the page's native fetch
  // untouched so we can determine whether main-world interception is causing
  // ChatGPT SPA controls to fail. This branch is disposable and must not merge.
})();
