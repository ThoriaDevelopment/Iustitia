/* Iustitia — scroll-reveal. Adds .in-view to elements carrying a detect.ac-style
   entrance class (fade-in-scroll / slide-in-left|right|bottom / zoom-in) once they
   enter the viewport, so the CSS transition animates them in. Staggered via .delay-N.
   Respects prefers-reduced-motion (renders everything visible immediately). */
(function () {
  var sel = ".fade-in-scroll, .slide-in-left, .slide-in-right, .slide-in-bottom, .zoom-in";
  var els = document.querySelectorAll(sel);
  if (!els.length) return;

  var reduce = window.matchMedia &&
    window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduce || !("IntersectionObserver" in window)) {
    els.forEach(function (el) { el.classList.add("in-view"); });
    return;
  }

  var io = new IntersectionObserver(function (entries, obs) {
    entries.forEach(function (e) {
      if (e.isIntersecting) {
        e.target.classList.add("in-view");
        obs.unobserve(e.target);
      }
    });
  }, { threshold: 0.12, rootMargin: "0px 0px -8% 0px" });

  els.forEach(function (el) { io.observe(el); });
})();