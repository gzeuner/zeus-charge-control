const { test, expect } = require("@playwright/test");
const statusRoute = /\/wallbox\/status(?:;[^?]*)?(?:\?.*)?$/;

test("wallbox renders status before and after the first read and recovers from HTTP failure", async ({
  page,
  request,
}) => {
  const errors = [];
  page.on("pageerror", (error) => errors.push(error.message));
  page.on("console", (message) => {
    if (
      message.type() === "error" &&
      message.text().includes("rendering failed")
    ) {
      errors.push(message.text());
    }
  });
  const data = await (await request.get("/wallbox/status")).json();
  data.settings = { ...data.settings, enabled: true, mode: "ECO" };
  data.featureEnabled = true;
  data.activeMode = "ECO";
  data.modbusState = "CONNECTED";
  data.modbusError = null;
  data.sample = null;
  data.lastSuccessfulRead = null;
  data.watchdogPossible = false;
  data.pauseUnconfirmed = false;
  data.pollIntervalMs = 400;
  let httpFailure = false;
  await page.route(statusRoute, (route) =>
    httpFailure
      ? route.fulfill({ status: 503, body: "unavailable" })
      : route.fulfill({ json: data }),
  );
  await page.goto("/charging-status?lang=de");
  const panel = page.locator("#wallboxPanel");
  await expect(page.locator("#wbControlState")).toHaveText("Steuerung ein");
  await expect(page.locator("#wbWarning")).toBeHidden();
  await expect(page.locator("#wbAccessLine")).toBeHidden();
  await expect(panel.getByRole("switch")).toBeEnabled();

  data.lastSuccessfulRead = new Date().toISOString();
  data.lastCommand = { command: "HR8", amps: 7, at: data.lastSuccessfulRead };
  data.boxReportedAppliedAmps = null;
  data.appliedAmpsRawHex = "0000 0000";
  data.sample = {
    connected: true,
    targetAmps: 7,
    availableWatts: 4830,
    pvSource: "Sonnen",
    charger: {
      status: 4,
      phases: 5,
      hardwarePhases: 3,
      selectedMode: 1,
      installedMaxAmps: 16,
      powerKw: 4.83,
      l1: { amps: 7 },
      l2: { amps: 7 },
      l3: { amps: 7 },
    },
    battery: { Production_W: 6500, Consumption_W: 1670, GridFeedIn_W: 0 },
  };
  await expect(page.locator("#wbStateText")).toHaveText("Lädt");
  await expect(panel.locator('[data-metric="power"]')).toHaveText("4,83 kW");
  await expect(panel.locator('[data-metric="phaseUse"]').first()).toContainText(
    "3 (L1, L2, L3",
  );
  await expect(panel.locator('[data-metric="target"]')).toContainText(
    "7,0 A (HR 8)",
  );
  await expect(panel.locator('[data-metric="reported"]')).toHaveText(
    "nicht verfügbar",
  );
  await expect(page.locator("#wbAccessLine")).toBeVisible();
  await expect(page.locator("#wbAccess")).toHaveAttribute(
    "datetime",
    data.lastSuccessfulRead,
  );
  await expect(page.locator("#wbWarning")).toBeHidden();

  const overflowingLabels = await panel
    .locator(".wb-energy dt")
    .evaluateAll(
      (labels) =>
        labels.filter((label) => label.scrollWidth > label.clientWidth + 1)
          .length,
    );
  expect(overflowingLabels).toBe(0);
  await panel.screenshot({ path: test.info().outputPath("wallbox-live.png") });

  data.lastCommand = {
    command: "PAUSE_HR2",
    amps: null,
    at: data.lastSuccessfulRead,
  };
  data.sample.targetAmps = 0;
  await expect(panel.locator('[data-metric="target"]')).toContainText(
    "Pause (HR 2)",
  );
  await expect(page.locator("#wbStateText")).toHaveText("Von App pausiert");

  httpFailure = true;
  await expect(page.locator("#wbWarning")).toHaveText(
    "Verbindung zu Zeus unterbrochen.",
  );
  httpFailure = false;
  await expect(page.locator("#wbWarning")).toBeHidden();
  await expect(page.locator("#wbAccessLine")).toBeVisible();
  await expect(panel.getByRole("switch")).toBeEnabled();
  expect(errors).toEqual([]);
});

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
    data.lastSuccessfulRead = data.lastAccess;
    data.modbusState = "CONNECTED";
    data.modbusError = null;
    data.watchdogPossible = false;
    data.pauseUnconfirmed = false;
    data.pollIntervalMs = 400;
    await page.route(statusRoute, (route) => route.fulfill({ json: data }));
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
      "Pausebefehl unbestätigt",
    );
    await expect(page.locator("#wbWarning")).toContainText(
      "SocketTimeoutException",
    );
    await expect(panel.locator('[data-metric="power"]')).toHaveText("–");
    await expect(page.locator("#wbMin")).toHaveValue("42");
    await page.unroute(statusRoute);
    await panel.getByRole("switch").click();
    await expect(panel.getByRole("switch")).toHaveAttribute(
      "aria-checked",
      "false",
    );
  } finally {
    await request.post(url, { data: initial });
  }
});
