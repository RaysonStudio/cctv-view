/* ==========================================================================
   央视网 TV — site interactions
   Progressive enhancement only: all content works without JavaScript.
   ========================================================================== */

(function () {
  "use strict";

  var doc = document;
  var body = doc.body;
  var header = doc.getElementById("site-header");
  var navToggle = doc.getElementById("nav-toggle");
  var navLinks = doc.getElementById("nav-links");
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* Header background on scroll ------------------------------------------- */

  var ticking = false;

  function updateHeader() {
    if (header) {
      header.classList.toggle("is-scrolled", window.scrollY > 8);
    }
    ticking = false;
  }

  window.addEventListener("scroll", function () {
    if (!ticking) {
      window.requestAnimationFrame(updateHeader);
      ticking = true;
    }
  }, { passive: true });

  updateHeader();

  /* Mobile navigation ------------------------------------------------------ */

  function closeNav() {
    body.classList.remove("nav-open");
    if (navToggle) {
      navToggle.setAttribute("aria-expanded", "false");
      navToggle.setAttribute("aria-label", "打开菜单");
    }
  }

  if (navToggle) {
    navToggle.addEventListener("click", function () {
      var open = body.classList.toggle("nav-open");
      navToggle.setAttribute("aria-expanded", open ? "true" : "false");
      navToggle.setAttribute("aria-label", open ? "关闭菜单" : "打开菜单");
    });
  }

  if (navLinks) {
    navLinks.addEventListener("click", function (event) {
      if (event.target.closest("a")) {
        closeNav();
      }
    });
  }

  doc.addEventListener("click", function (event) {
    if (!body.classList.contains("nav-open")) return;
    if (header && !header.contains(event.target)) {
      closeNav();
    }
  });

  doc.addEventListener("keydown", function (event) {
    if (event.key === "Escape") {
      closeNav();
    }
  });

  /* Scroll reveal ---------------------------------------------------------- */

  var revealItems = Array.prototype.slice.call(doc.querySelectorAll(".reveal"));

  if (reduceMotion || !("IntersectionObserver" in window)) {
    revealItems.forEach(function (el) { el.classList.add("is-in"); });
  } else {
    var revealObserver = new IntersectionObserver(function (entries, observer) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        var el = entry.target;
        var siblings = Array.prototype.slice.call(el.parentElement ? el.parentElement.children : []);
        var index = Math.max(0, siblings.indexOf(el));
        el.style.transitionDelay = Math.min(index * 55, 260) + "ms";
        el.classList.add("is-in");
        observer.unobserve(el);
      });
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });

    revealItems.forEach(function (el) { revealObserver.observe(el); });
  }

  /* Scroll spy ------------------------------------------------------------- */

  var sectionIds = ["features", "channels", "remote", "install", "faq"];
  var spyLinks = {};

  sectionIds.forEach(function (id) {
    var link = doc.querySelector('.nav-links a[href="#' + id + '"]');
    var section = doc.getElementById(id);
    if (link && section) {
      spyLinks[id] = { link: link, section: section };
    }
  });

  var spyTicking = false;

  function updateSpy() {
    var offset = 140;
    var current = null;

    Object.keys(spyLinks).forEach(function (id) {
      var top = spyLinks[id].section.getBoundingClientRect().top;
      if (top <= offset) {
        current = id;
      }
    });

    Object.keys(spyLinks).forEach(function (id) {
      var active = id === current;
      spyLinks[id].link.classList.toggle("is-active", active);
      if (active) {
        spyLinks[id].link.setAttribute("aria-current", "true");
      } else {
        spyLinks[id].link.removeAttribute("aria-current");
      }
    });

    spyTicking = false;
  }

  window.addEventListener("scroll", function () {
    if (!spyTicking) {
      window.requestAnimationFrame(updateSpy);
      spyTicking = true;
    }
  }, { passive: true });

  updateSpy();

  /* Channel filter --------------------------------------------------------- */

  var filterButtons = Array.prototype.slice.call(doc.querySelectorAll(".chip-btn"));
  var channels = Array.prototype.slice.call(doc.querySelectorAll(".channel"));
  var channelCount = doc.getElementById("channel-count");
  var filterLabels = {
    all: "全部",
    cctv: "央视",
    local: "地方",
    uhd: "超高清"
  };

  function applyFilter(filter) {
    var visible = 0;

    channels.forEach(function (channel) {
      var cats = (channel.getAttribute("data-cat") || "").split(/\s+/);
      var show = filter === "all" || cats.indexOf(filter) !== -1;
      channel.hidden = !show;
      channel.classList.remove("is-filtered-in");
      if (show) {
        visible += 1;
        // Restart the entry animation.
        void channel.offsetWidth;
        channel.classList.add("is-filtered-in");
      }
    });

    if (channelCount) {
      channelCount.textContent = filter === "all"
        ? "显示全部 " + visible + " 个频道"
        : "显示" + filterLabels[filter] + "频道 " + visible + " 个";
    }

    filterButtons.forEach(function (button) {
      var active = button.getAttribute("data-filter") === filter;
      button.classList.toggle("is-active", active);
      button.setAttribute("aria-pressed", active ? "true" : "false");
    });
  }

  filterButtons.forEach(function (button) {
    button.addEventListener("click", function () {
      applyFilter(button.getAttribute("data-filter") || "all");
    });
  });

  /* Footer year ------------------------------------------------------------ */

  var year = doc.getElementById("year");
  if (year) {
    year.textContent = String(new Date().getFullYear());
  }

  /* Prevent placeholder jumps for empty anchors ---------------------------- */

  doc.querySelectorAll('a[href="#"]').forEach(function (link) {
    link.addEventListener("click", function (event) {
      event.preventDefault();
    });
  });
})();
