// Browser assets are checked in: the deployed application needs neither Node nor a CDN.
const fs = require("node:fs");
const path = require("node:path");
const root = path.resolve(__dirname, "..");
const destination = path.join(root, "src/main/resources/static");
const assets = {
  "bootstrap/dist/css/bootstrap.min.css": "css/bootstrap.min.css",
  "bootstrap/dist/js/bootstrap.bundle.min.js": "js/bootstrap.bundle.min.js",
  "chart.js/dist/chart.umd.min.js": "js/chart.min.js",
  "luxon/build/global/luxon.min.js": "js/luxon.min.js",
  "chartjs-adapter-luxon/dist/chartjs-adapter-luxon.umd.min.js":
    "js/chartjs-adapter-luxon.umd.js",
  "@fortawesome/fontawesome-free/webfonts/fa-solid-900.woff2":
    "webfonts/fa-solid-900.woff2",
};
for (const [source, target] of Object.entries(assets)) {
  fs.copyFileSync(
    path.join(root, "node_modules", source),
    path.join(destination, target),
  );
}
const iconCss = ["fontawesome.min.css", "solid.min.css"]
  .map((file) =>
    fs.readFileSync(
      path.join(root, "node_modules/@fortawesome/fontawesome-free/css", file),
      "utf8",
    ),
  )
  .join("\n");
fs.writeFileSync(path.join(destination, "css/all.min.css"), iconCss);
// The optional devtools source maps are not runtime dependencies.
for (const target of Object.values(assets).filter((file) =>
  /\.(css|js)$/.test(file),
)) {
  const file = path.join(destination, target);
  const contents = fs
    .readFileSync(file, "utf8")
    .replace(/\/\*[#@] sourceMappingURL=.*?\*\//g, "")
    .replace(/^\/\/# sourceMappingURL=.*$/gm, "")
    .trimEnd();
  fs.writeFileSync(file, contents + "\n");
}
const licenses = {
  bootstrap: "LICENSE",
  "chart.js": "LICENSE.md",
  luxon: "LICENSE.md",
  "chartjs-adapter-luxon": "LICENSE.md",
  "@fortawesome/fontawesome-free": "LICENSE.txt",
};
fs.mkdirSync(path.join(destination, "vendor-licenses"), { recursive: true });
for (const [name, file] of Object.entries(licenses)) {
  fs.copyFileSync(
    path.join(root, "node_modules", name, file),
    path.join(
      destination,
      "vendor-licenses",
      name.replace(/[@/]/g, "") + ".txt",
    ),
  );
}
console.log("Pinned browser assets and original licenses synchronized.");
