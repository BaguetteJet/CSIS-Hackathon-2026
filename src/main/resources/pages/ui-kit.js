// ui-kit.js: fills <header class="gb-header"> with the brand, your navigation and the signed-in user.
//
//   <header class="gb-header" data-title="Restaurant" data-nav="Deals|deals.html, Scan|scan.html"></header>
//   <script type="module" src="ui-kit.js"></script>
//
// data-title names the area (Restaurant, Students' Union, ...); data-nav lists "Label|link" pairs.
// The link matching this page is highlighted.

import { currentUser, escapeHtml, signInUrl, signOut } from "./api.js";

const ROLE_NAMES = {
  STUDENT: "Student",
  RESTAURANT_STAFF: "Restaurant staff",
  UNION_SUPPORT: "Students' Union, support",
  UNION_FINANCE: "Students' Union, finance",
  UNION_ADMIN: "Students' Union, admin",
};

for (const header of document.querySelectorAll("header.gb-header")) {
  const title = header.dataset.title || "";
  const links = (header.dataset.nav || "")
    .split(",")
    .map((s) => s.trim())
    .filter(Boolean)
    .map((pair) => pair.split("|").map((s) => s.trim()));
  const here = location.pathname.split("/").pop() || "index.html";
  const user = currentUser();

  header.innerHTML = `
    <div class="gb-header-inner">
      <div class="gb-brand">Campus Group-Buy ${title ? `<span>${escapeHtml(title)}</span>` : ""}</div>
      <nav class="gb-nav">${links
        .map(([label, href]) => `<a href="${escapeHtml(href)}"${href === here ? ' aria-current="page"' : ""}>${escapeHtml(label)}</a>`)
        .join("")}</nav>
      <div class="gb-user">${
        user
          ? `${escapeHtml(user.name || ROLE_NAMES[user.role] || user.role)} <a href="#" data-sign-out>Sign out</a>`
          : `<a href="${escapeHtml(signInUrl())}">Sign in</a>`
      }</div>
    </div>`;
  header.querySelector("[data-sign-out]")?.addEventListener("click", (e) => {
    e.preventDefault();
    signOut();
  });
}
