const { test, expect } = require("@playwright/test");
const fs = require("node:fs");
const path = require("node:path");
test("capture all five themes with clearly marked sample data", async ({
  page,
  request,
}, testInfo) => {
  await request.post("/__test/reset");
  await page.goto("/charging-status?lang=de");
  await expect(page.locator("#capacityPercentValue")).toHaveText("64");
  await expect(page.locator("#connectionLabel")).toHaveText(
    "Auf aktuellem Stand",
  );
  await page.evaluate(() => document.fonts.ready);
  const dir = path.resolve("images/screenshots");
  fs.mkdirSync(dir, { recursive: true });
  for (const [theme, name] of Object.entries({
    default: "nord",
    dark: "midnight",
    energy: "forest",
    solar: "solar",
    ember: "ember",
  })) {
    await page.selectOption("#themeSelector", theme);
    await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
    await page.screenshot({
      path: path.join(dir, `dashboard-${name}-${testInfo.project.name}.png`),
      fullPage: true,
      animations: "disabled",
    });
  }
});
