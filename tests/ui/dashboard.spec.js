const { test, expect } = require("@playwright/test");
const AxeBuilder = require("@axe-core/playwright").default;

async function openDashboard(page, language = "de") {
  await page.goto(`/charging-status?lang=${language}`);
  await expect(page.locator("#connectionLabel")).toHaveText(
    language === "de" ? "Auf aktuellem Stand" : "Up to date",
  );
}
test.beforeEach(async ({ request }) => {
  await request.post("/__test/reset");
});

test("live dashboard renders real templates, chart and missing metrics without browser errors", async ({
  page,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await openDashboard(page);
  await expect(page.locator("#capacityPercentValue")).toHaveText("64");
  await expect(page.locator("#capacityKwhValue")).toHaveText("6,4");
  await expect(page.locator("#productionValue")).toHaveText("3.200");
  await expect(page.locator("#dropRateValue")).toHaveText("—");
  await expect(page.locator("#currentPriceValue")).toHaveText("8,42");
  await expect(page.locator("#modeStatus")).toHaveText("Automatik");
  await expect(page.locator("#chargeRing")).toHaveAttribute(
    "aria-valuenow",
    "64",
  );
  await expect(page.locator("#cheapestPriceValue")).toHaveText("4,25");
  await expect
    .poll(() =>
      page.evaluate(
        () => Chart.getChart("marketPricesChart")?.data.datasets[0].data.length,
      ),
    )
    .toBe(30);
  expect(errors).toEqual([]);
});

test("themes persist and remain usable after reload", async ({ page }) => {
  await openDashboard(page);
  for (const theme of ["default", "dark", "energy", "solar", "ember"]) {
    await page.selectOption("#themeSelector", theme);
    await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
    expect(
      await page.evaluate(
        () => Chart.getChart("marketPricesChart").data.datasets[0].borderColor,
      ),
    ).toBeTruthy();
  }
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "ember");
  await expect(page.locator("#themeSelector")).toHaveValue("ember");
});

test("all price views support chart/table, persistence and keyboard tabs", async ({
  page,
}) => {
  await openDashboard(page);
  for (const [tab, button, prefix] of [
    ["market-prices", "toggleMarketPrices", "marketPrices"],
    [
      "scheduled-charging-periods",
      "toggleChargingSchedule",
      "chargingSchedule",
    ],
    ["cheapest-periods", "toggleCheapestPeriods", "cheapestPeriods"],
  ]) {
    await page.locator(`#${tab}-tab`).click();
    await expect(page.locator(`#${prefix}ChartContainer`)).toBeVisible();
    await page.locator(`#${button}`).click();
    await expect(page.locator(`#${prefix}Table`)).toBeVisible();
    expect(await page.locator(`#${prefix}Tbody tr`).count()).toBeGreaterThan(0);
    await page.locator(`#${button}`).click();
    await expect(page.locator(`#${prefix}ChartContainer`)).toBeVisible();
  }
  await page.locator("#toggleCheapestPeriods").click();
  await page.reload();
  await expect(page.locator("#cheapest-periods-tab")).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await expect(page.locator("#cheapestPeriodsTable")).toBeVisible();
  await page.locator("#market-prices-tab").focus();
  await page.keyboard.press("ArrowRight");
  await expect(page.locator("#scheduled-charging-periods-tab")).toBeFocused();
  await expect(page.locator("#scheduled-charging-periods-tab")).toHaveAttribute(
    "aria-selected",
    "true",
  );
});

test("price basis changes cards, charts and tables consistently, including negative prices", async ({
  page,
}) => {
  await openDashboard(page);
  await page.locator("#priceModeToggle").check();
  await expect(page.locator("#currentPriceValue")).toHaveText("29,03");
  await expect(page.locator("#cheapestPriceValue")).toHaveText("24,07");
  for (const label of await page.locator(".price-basis").allTextContents())
    expect(label).toBe("Gesamt · inkl. MwSt.");
  await expect
    .poll(() =>
      page.evaluate(
        () => Chart.getChart("marketPricesChart").data.datasets[0].data[0].y,
      ),
    )
    .toBe(7.11);
  await page.locator("#toggleMarketPrices").click();
  await expect(
    page.locator("#marketPricesTbody tr").first().locator("td").last(),
  ).toHaveText("7,11");
  await page.reload();
  await expect(page.locator("#priceModeToggle")).toBeChecked();
  await page.locator("#priceModeToggle").uncheck();
  await expect(
    page.locator("#marketPricesTbody tr").first().locator("td").last(),
  ).toHaveText("-10,00");
});

test("pause and automatic actions update acknowledged state without navigation", async ({
  page,
}) => {
  await openDashboard(page);
  let navigations = 0;
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigations++;
  });
  await page.locator("#modeBtn").click();
  await expect(page.locator("#modeStatus")).toHaveText("Pausiert");
  await expect(page.locator("#modeBtn")).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await expect(page.locator("#setpointValue")).toHaveText("1");
  await page.locator("#automaticBtn").click();
  await expect(page.locator("#modeStatus")).toHaveText("Automatik");
  await expect(page.locator("#automaticBtn")).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  expect(navigations).toBe(0);
});

test("grid charging requires explicit confirmation, stays open and supports cancel, Escape and stop", async ({
  page,
}) => {
  await page.clock.install();
  await openDashboard(page);
  let starts = 0;
  page.on("request", (request) => {
    if (
      request.url().endsWith("/toggle-charging") &&
      request.postData()?.includes("start")
    )
      starts++;
  });
  await page.locator("#chargingBtn").click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();
  await expect(page.locator("#cancelStart")).toBeFocused();
  await page.clock.fastForward(6000);
  await expect(dialog).toBeVisible();
  await expect(page.locator("#chargingBtn")).toHaveAttribute(
    "aria-pressed",
    "false",
  );
  await page.locator("#cancelStart").click();
  await expect(dialog).toBeHidden();
  await expect(page.locator("#chargingBtn")).toBeFocused();
  expect(starts).toBe(0);
  await page.locator("#chargingBtn").click();
  await expect(dialog).toBeVisible();
  await expect(page.locator("#cancelStart")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(page.locator("#chargingBtn")).toBeFocused();
  await page.locator("#chargingBtn").click();
  await page.locator("#confirmStart").click();
  await expect(page.locator("#modeStatus")).toHaveText("Lädt");
  await expect(page.locator("#chargingBtn [data-role=label]")).toHaveText(
    "Netzladen beenden",
  );
  expect(starts).toBe(1);
  await page.locator("#chargingBtn").click();
  await expect(page.locator("#modeStatus")).toHaveText("Automatik");
});

test("quiet hours validate input, protect unsaved edits during polling, save and toggle", async ({
  page,
}) => {
  await openDashboard(page);
  await page.locator("#nightStartInput").fill("22");
  await page.locator("#nightEndInput").fill("7");
  await page.locator("#refreshStatus").click();
  await expect(page.locator("#nightStartInput")).toHaveValue("22");
  await expect(page.locator("#nightEndInput")).toHaveValue("7");
  await expect(page.locator("#nightWindowStatus")).toHaveText(
    "Ungespeicherte Änderungen",
  );
  await page.locator("#nightStartInput").fill("1.5");
  await page.locator("#saveNightWindowBtn").click();
  await expect(page.locator("#nightWindowStatus")).toHaveText(/ganze Stunden/);
  await expect(page.locator("#nightStartInput")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  await page.locator("#nightStartInput").fill("7");
  await page.locator("#saveNightWindowBtn").click();
  await expect(page.locator("#nightWindowStatus")).toHaveText(/Verschiedene/);
  await page.locator("#nightStartInput").fill("22");
  await page.locator("#saveNightWindowBtn").click();
  await expect(page.locator("#nightWindowStatus")).toHaveText(
    "Ruhezeit für diese Sitzung gespeichert.",
  );
  await expect(page.locator("#nightStartInput")).toHaveValue("22");
  await page.locator("#nightChargingBtn").click();
  await expect(page.locator("#nightChargingBtn")).toHaveAttribute(
    "aria-checked",
    "true",
  );
  await expect(page.locator("#nightState")).toHaveText("Aktiviert");
  await page.locator("#nightChargingBtn").click();
  await expect(page.locator("#nightChargingBtn")).toHaveAttribute(
    "aria-checked",
    "false",
  );
});

test("rejected commands keep actual state and display a useful error", async ({
  page,
  request,
}) => {
  await openDashboard(page);
  await request.post("/__test/scenario?name=reject");
  await page.locator("#modeBtn").click();
  await expect(page.locator("#actionNotice")).toContainText(
    "Die Aktion konnte nicht abgeschlossen werden",
  );
  await expect(page.locator("#modeStatus")).toHaveText("Automatik");
  await expect(page.locator("#modeBtn")).toHaveAttribute(
    "aria-pressed",
    "false",
  );
  await expect(page.locator("#modeBtn")).toBeEnabled();
});

test("connection failures expose stale state, disable controls and recover on retry", async ({
  page,
}) => {
  await openDashboard(page);
  await page.route("**/current-status", (route) =>
    route.fulfill({ status: 503, body: "{}" }),
  );
  await page.locator("#refreshStatus").click();
  await expect(page.locator("#connectionNotice")).toBeVisible();
  await expect(page.locator("#capacityPercentValue")).toHaveText("64");
  await expect(page.locator("#modeBtn")).toBeDisabled();
  await page.unroute("**/current-status");
  await page.locator("#retryStatus").click();
  await expect(page.locator("#connectionNotice")).toBeHidden();
  await expect(page.locator("#modeBtn")).toBeEnabled();
});

test("periodic refresh updates prices without reloading or losing unsaved hours", async ({
  page,
  request,
}) => {
  await page.clock.install();
  await openDashboard(page);
  let navigations = 0;
  page.on("framenavigated", (frame) => {
    if (frame === page.mainFrame()) navigations++;
  });
  await page.locator("#nightStartInput").fill("22");
  await request.post("/__test/scenario?name=empty");
  await page.clock.fastForward(70000);
  await expect(page.locator("#marketPricesEmpty")).toBeVisible();
  await expect(page.locator("#nightStartInput")).toHaveValue("22");
  expect(navigations).toBe(0);
});

test("a pending command cannot be submitted twice", async ({ page }) => {
  await openDashboard(page);
  let release;
  let commands = 0;
  const gate = new Promise((resolve) => {
    release = resolve;
  });
  await page.route("**/toggle-mode", async (route) => {
    commands++;
    await gate;
    await route.continue();
  });
  await page.locator("#modeBtn").click();
  await expect(page.locator("#modeBtn")).toBeDisabled();
  await page.locator("#modeBtn").dispatchEvent("click");
  release();
  await expect(page.locator("#modeStatus")).toHaveText("Pausiert");
  expect(commands).toBe(1);
});

test("missing battery, empty prices and plans remain clear and recover without reload", async ({
  page,
  request,
}) => {
  await request.post("/__test/scenario?name=empty");
  await request.post("/__test/scenario?name=unavailable");
  await page.goto("/charging-status?lang=de");
  await expect(page.locator("#batteryWarning")).toBeVisible();
  await expect(page.locator("#capacityPercentValue")).toHaveText("—");
  await expect(page.locator("#currentPriceValue")).toHaveText("—");
  await expect(page.locator("#dropRateValue")).toHaveText("—");
  await expect(page.locator("#modeBtn")).toBeDisabled();
  await expect(page.locator("#marketPricesEmpty")).toBeVisible();
  await page.locator("#scheduled-charging-periods-tab").click();
  await expect(page.locator("#chargingScheduleEmpty")).toContainText(
    "Aktuell keine Ladefenster",
  );
  await request.post("/__test/scenario?name=recover");
  await page.locator("#refreshStatus").click();
  await expect(page.locator("#capacityPercentValue")).toHaveText("64");
  await expect(page.locator("#batteryWarning")).toBeHidden();
  await expect(page.locator("#modeBtn")).toBeEnabled();
});

test("unconfigured battery shows configuration guidance without a template error", async ({
  page,
  request,
}) => {
  await request.post("/__test/scenario?name=unconfigured");
  await page.goto("/charging-status?lang=de");
  await expect(page.locator("#batteryWarning")).toContainText("Konfiguration");
  await expect(page.locator("#modeBtn")).toBeDisabled();
});

test("language switch and license navigation work", async ({ page }) => {
  await openDashboard(page);
  await page.selectOption("#languageSelector", "en");
  await expect(page.locator("html")).toHaveAttribute("lang", "en");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Energy, beautifully in control.",
  );
  await expect(page.locator("#currentPriceValue")).toHaveText("8.42");
  await page.getByRole("link", { name: "License", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "License Terms",
  );
});

test("malformed preferences fall back safely", async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem("chargeControlUiState", "{broken");
    localStorage.setItem("chargeControlTheme", "unknown");
    localStorage.setItem("priceMode", "invalid");
  });
  await openDashboard(page);
  await expect(page.locator("html")).toHaveAttribute("data-theme", "default");
  await expect(page.locator("#market-prices-tab")).toHaveAttribute(
    "aria-selected",
    "true",
  );
  await expect(page.locator("#currentPriceValue")).toHaveText("8,42");
});

test("WCAG accessibility checks pass in all themes and in the confirmation dialog", async ({
  page,
}) => {
  test.setTimeout(90000);
  await openDashboard(page);
  for (const theme of ["default", "dark", "energy", "solar", "ember"]) {
    await page.selectOption("#themeSelector", theme);
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
      .analyze();
    expect(
      results.violations,
      `${theme}: ${JSON.stringify(results.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target) })))}`,
    ).toEqual([]);
  }
  await page.locator("#chargingBtn").click();
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
});

test("layout fits narrow phones, tablets and desktop without page overflow", async ({
  page,
}) => {
  await openDashboard(page);
  for (const width of [320, 390, 768, 1024, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    await expect
      .poll(() => page.evaluate(() => document.documentElement.scrollWidth))
      .toBeLessThanOrEqual(width);
  }
});
