const { test, expect } = require("@playwright/test");

test("wallbox modes, Smart limits, live readings and loss of connection", async ({
  page,
  request,
}) => {
  const url = "/wallbox/settings";
  const initial = {
    enabled: false,
    mode: "ECO",
    smartMinUsoc: 20,
    smartReservePercent: 5,
  };
  await request.post(url, { data: initial });
  try {
    await page.goto("/charging-status?lang=de");
    const panel = page.locator("#wallboxPanel");
    await expect(
      page.locator("main > .data-section + #wallboxPanel"),
    ).toHaveCount(1);
    const smart = panel.getByRole("button", { name: "Smart", exact: true });
    await panel.getByRole("button", { name: "Power", exact: true }).click();
    await expect(
      panel.getByRole("button", { name: "Power", exact: true }),
    ).toHaveAttribute("aria-pressed", "true");
    await smart.click();
    await page.locator("#wbMin").fill("101");
    await expect(page.locator("#wbMin:invalid")).toHaveCount(1);
    await page.locator("#wbMin").fill("37");
    await page.locator("#wbReserve").fill("7");
    await page.locator("#wbSmart button").click();
    await expect
      .poll(
        async () =>
          (await (await request.get("/wallbox/status")).json()).settings
            .smartMinUsoc,
      )
      .toBe(37);
    await page.reload();
    await expect(smart).toHaveAttribute("aria-pressed", "true");
    await expect(page.locator("#wbMin")).toHaveValue("37");
    await expect(page.locator("#wbReserve")).toHaveValue("7");
    await panel.getByRole("switch").click();
    await expect(panel.getByRole("switch")).toHaveAttribute(
      "aria-checked",
      "true",
    );
    await expect(page.locator("#wbLink")).toHaveText(/Reconnect|Fehler/, {
      timeout: 12000,
    });

    // Provide repeatable measurements to the real dashboard. The test server uses loopback only.
    const data = await (await request.get("/wallbox/status")).json();
    data.sample = {
      connected: true,
      targetAmps: 7,
      availableWatts: 4830,
      charger: {
        status: 4,
        phases: 0,
        selectedMode: 1,
        powerKw: 4.83,
        l1: { amps: 7 },
        l2: { amps: 7 },
        l3: { amps: 7 },
      },
      battery: {
        Production_W: 6500,
        Consumption_W: 6400,
        GridFeedIn_W: -150,
        USOC: 71,
        Pac_total_W: 300,
      },
    };
    data.lastAccess = new Date().toISOString();
    data.modbusError = null;
    data.watchdogPossible = false;
    data.pauseUnconfirmed = false;
    data.pollIntervalMs = 400;
    await page.route("**/wallbox/status", (route) =>
      route.fulfill({ json: data }),
    );
    await page.reload();
    await expect(page.locator("#wbState")).toHaveText("Lädt");
    await expect(page.locator("#wbWarning")).toBeHidden();
    await expect(panel.locator('[data-metric="power"]')).toHaveText("4,83 kW");
    await expect(panel.locator('[data-metric="available"]')).toHaveText(
      "4.830 W",
    );
    await expect(panel.locator('[data-metric="grid"]')).toHaveText("-150 W");
    await page.locator("#wbMin").fill("42");
    data.sample = {
      charger: null,
      battery: null,
      connected: false,
      targetAmps: null,
      error: "SocketTimeoutException",
    };
    data.watchdogPossible = true;
    data.pauseUnconfirmed = true;
    await expect(page.locator("#wbFallback")).toBeVisible();
    await expect(page.locator("#wbWarning")).toContainText(
      "0-A-Pause unbestätigt",
    );
    await expect(page.locator("#wbWarning")).toContainText(
      "SocketTimeoutException",
    );
    await expect(panel.locator('[data-metric="power"]')).toHaveText("–");
    await expect(page.locator("#wbMin")).toHaveValue("42");
    await page.unroute("**/wallbox/status");
    await panel.getByRole("switch").click();
    await expect(panel.getByRole("switch")).toHaveAttribute(
      "aria-checked",
      "false",
    );
  } finally {
    await request.post(url, { data: initial });
  }
});
