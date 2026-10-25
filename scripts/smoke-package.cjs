// Exercises the actual ZIP, configuration and native launchers. No physical battery calls.
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const http = require("node:http");
const net = require("node:net");
const { spawn, execFileSync } = require("node:child_process");

const root = path.resolve(__dirname, "..");
const archive = path.resolve(process.argv[2] || "");
const packageName = process.argv[3] || "";
const windows = process.platform === "win32";
const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};
const hash = (file) =>
  crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");

function findJdk() {
  const portable = path.join(root, ".tools/jdk21");
  const candidates = fs.existsSync(portable)
    ? fs
        .readdirSync(portable)
        .sort()
        .reverse()
        .map((name) => path.join(portable, name))
    : [];
  if (process.env.JAVA_HOME) candidates.push(process.env.JAVA_HOME);
  try {
    const discovered = execFileSync(windows ? "where.exe" : "which", ["java"], {
      encoding: "utf8",
      windowsHide: true,
    })
      .trim()
      .split(/\r?\n/)[0];
    candidates.push(path.dirname(path.dirname(fs.realpathSync(discovered))));
  } catch (_) {
    /* The explicit JDK candidates can still work. */
  }
  for (const candidate of candidates) {
    const release = path.join(candidate, "release");
    const jar = path.join(candidate, "bin", windows ? "jar.exe" : "jar");
    if (fs.existsSync(release) && fs.existsSync(jar)) {
      const major = fs
        .readFileSync(release, "utf8")
        .match(/^JAVA_VERSION="(\d+)/m);
      if (major && Number(major[1]) >= 21) return { home: candidate, jar };
    }
  }
  throw new Error("The package smoke test requires a JDK 21 or newer.");
}
function bashExecutable() {
  if (!windows) return "bash";
  const candidates = [
    process.env.BASH_EXE,
    path.join(
      process.env.ProgramFiles || "C:/Program Files",
      "Git/bin/bash.exe",
    ),
    path.join(process.env.LOCALAPPDATA || "", "Programs/Git/bin/bash.exe"),
  ];
  const found = candidates.find(
    (candidate) => candidate && fs.existsSync(candidate),
  );
  assert(found, "Install Git Bash to verify the Unix launcher from Windows.");
  return found;
}
async function freePort() {
  const socket = net.createServer();
  await new Promise((resolve, reject) => {
    socket.once("error", reject);
    socket.listen(0, "127.0.0.1", resolve);
  });
  const port = socket.address().port;
  await new Promise((resolve) => socket.close(resolve));
  return port;
}
function launch(kind, appDir, args, jdk, bash, label) {
  const env = { ...process.env, JAVA_HOME: jdk.home.replace(/\\/g, "/") };
  const logFile = path.join(path.dirname(appDir), `${label}.log`);
  const output = fs.openSync(logFile, "w");
  let child;
  if (kind === "windows") {
    // Only trusted generated arguments enter cmd; no credentials or user-supplied commands.
    const launcher = path.join(appDir, "start.bat");
    assert(
      !/["%\r\n]/.test(launcher) &&
        args.every((arg) => /^[A-Za-z0-9_./:=;-]*$/.test(arg)),
      "Unsafe cmd argument.",
    );
    const command = `""${launcher}" ${args.map((arg) => `"${arg}"`).join(" ")}"`;
    child = spawn(
      process.env.ComSpec || "cmd.exe",
      ["/d", "/s", "/c", command],
      {
        windowsVerbatimArguments: true,
        windowsHide: true,
        cwd: root,
        env,
        stdio: ["ignore", output, output],
      },
    );
  } else if (kind === "jar") {
    child = spawn(
      path.join(jdk.home, "bin", windows ? "java.exe" : "java"),
      ["-jar", path.join(appDir, "lib/zeus-charge-control.jar"), ...args],
      {
        windowsHide: true,
        cwd: appDir,
        env,
        stdio: ["ignore", output, output],
      },
    );
  } else {
    child = spawn(
      bash,
      [path.join(appDir, "start.sh").replace(/\\/g, "/"), ...args],
      {
        windowsHide: true,
        cwd: root,
        env,
        stdio: ["ignore", output, output],
      },
    );
  }
  fs.closeSync(output);
  child.on("error", (error) => {
    child.launchError = error;
  });
  return { child, logFile };
}
async function stopOwnChild(child) {
  if (child.exitCode !== null || child.launchError) return;
  const exited = new Promise((resolve) => child.once("exit", resolve));
  if (windows) {
    // Only the process tree launched immediately above; never kill by image name or port.
    try {
      execFileSync("taskkill.exe", ["/pid", String(child.pid), "/t", "/f"], {
        stdio: "ignore",
        windowsHide: true,
      });
    } catch (_) {
      /* The process may already have exited. */
    }
  } else child.kill("SIGTERM");
  await Promise.race([exited, delay(5000)]);
  if (!windows && child.exitCode === null) child.kill("SIGKILL");
}
async function expectMissingConfig(kind, appDir, jdk, bash) {
  const { child, logFile } = launch(
    kind,
    appDir,
    [],
    jdk,
    bash,
    `${kind}-missing-config`,
  );
  try {
    const code = await Promise.race([
      new Promise((resolve) => child.once("exit", resolve)),
      delay(10000).then(() => {
        throw new Error(
          `${kind}: launcher did not fail on missing configuration`,
        );
      }),
    ]);
    assert(
      code === 2 &&
        fs.readFileSync(logFile, "utf8").includes("Configuration missing."),
      `${kind}: missing-config guard failed`,
    );
    console.log(
      `PASS ${kind}: missing configuration fails clearly before JVM startup`,
    );
  } finally {
    await stopOwnChild(child);
  }
}
async function checkRunningPackage(kind, appDir, jdk, bash, pricePort) {
  const port = await freePort();
  const args = [
    `--server.address=127.0.0.1`,
    `--server.port=${port}`,
    "--battery.url=",
    "--battery.authToken=",
    "--marketdata.source=awattar",
    `--awattar.marketdata.url=http://127.0.0.1:${pricePort}/marketdata`,
    "--awattar.authToken=",
    `--tibber.marketdata.url=http://127.0.0.1:${pricePort}/marketdata`,
    "--tibber.authToken=",
    "--weather.api.enabled=false",
    "--scheduled.job.cron=-",
    "--battery.automatic.mode.check.interval=3600000",
    "--spring.main.banner-mode=off",
  ];
  if (kind === "jar") args.push("--spring.config.location=classpath:/");
  const { child, logFile } = launch(
    kind,
    appDir,
    args,
    jdk,
    bash,
    `${kind}-startup`,
  );
  try {
    let status;
    for (let attempt = 0; attempt < 120; attempt++) {
      if (child.launchError) throw child.launchError;
      if (child.exitCode !== null)
        throw new Error(`${kind}: application exited (${child.exitCode})`);
      try {
        const response = await fetch(
          `http://127.0.0.1:${port}/current-status`,
          { signal: AbortSignal.timeout(1000) },
        );
        if (response.ok) {
          status = await response.json();
          break;
        }
      } catch (_) {
        /* Wait for the JVM and embedded server to finish starting. */
      }
      await delay(500);
    }
    assert(status, `${kind}: application startup timed out`);
    assert(
      status.batteryNotConfigured === true && status.batteryAvailable === false,
      `${kind}: hardware isolation failed`,
    );
    assert(
      status.marketPrices.length > 0,
      `${kind}: isolated price data was not loaded`,
    );
    for (const [lang, heading, quietHours] of [
      ["de", "Deine Energie. Bestens geplant.", "Nachtruhe"],
      ["en", "Energy, beautifully in control.", "Quiet hours"],
    ]) {
      const page = await fetch(
        `http://127.0.0.1:${port}/charging-status?lang=${lang}`,
      );
      const html = await page.text();
      assert(
        page.ok &&
          html.includes(heading) &&
          html.includes(quietHours) &&
          !html.includes("??"),
        `${kind}: dashboard translation failed for ${lang}`,
      );
    }
    const css = await fetch(`http://127.0.0.1:${port}/css/darkmode.css`);
    assert(
      css.ok && (await css.text()).includes("--battery-bg"),
      `${kind}: packaged browser asset missing`,
    );
    const demo = await fetch(`http://127.0.0.1:${port}/__test/reset`, {
      method: "POST",
    });
    assert(
      demo.status === 404,
      `${kind}: test endpoint present in deployed JAR`,
    );
    const consoleResponse = await fetch(`http://127.0.0.1:${port}/h2-console`);
    assert(consoleResponse.status === 404, `${kind}: H2 console was exposed`);
    const databaseName =
      kind === "jar" ? "standalone-jar" : "zeus-charge-control";
    assert(
      fs.existsSync(path.join(appDir, `data/${databaseName}.mv.db`)),
      `${kind}: persistent database configuration not loaded`,
    );
    console.log(
      `PASS ${kind}: real JAR, configured persistent H2, complete DE/EN labels, GUI/assets, argument forwarding and no test endpoints`,
    );
  } catch (error) {
    const tail = fs
      .readFileSync(logFile, "utf8")
      .split(/\r?\n/)
      .slice(-18)
      .join("\n");
    throw new Error(`${error.message}\n${tail}`);
  } finally {
    await stopOwnChild(child);
  }
}

(async () => {
  assert(
    /^zeus-charge-control-\d+\.\d+\.\d+(?:[-.][0-9A-Za-z]+)*$/.test(
      packageName,
    ),
    "Invalid distribution directory name.",
  );
  assert(fs.existsSync(archive), "Distribution ZIP missing.");
  const jdk = findJdk();
  const bash = bashExecutable();
  const names = execFileSync(jdk.jar, ["tf", archive], {
    encoding: "utf8",
    windowsHide: true,
  })
    .trim()
    .split(/\r?\n/);
  const allowed = new Set([
    "start.bat",
    "start.sh",
    "lib/zeus-charge-control.jar",
    "config/application.properties.example",
    "data/.gitkeep",
    "logs/.gitkeep",
    "LICENSE",
    "README.md",
    "build-info.json",
    "runtime-dependencies.txt",
    "SHA256SUMS",
  ]);
  for (const name of names) {
    assert(
      name.startsWith(`${packageName}/`) &&
        !name.includes("\\") &&
        !name.includes(":") &&
        !name.split("/").includes(".."),
      "Unsafe archive entry.",
    );
    const relative = name.slice(packageName.length + 1);
    if (relative && !relative.endsWith("/"))
      assert(
        allowed.has(relative) ||
          /^licenses\/browsers\/[a-z0-9][a-z0-9.-]*\.txt$/.test(relative),
        `Unexpected packaged file: ${relative}`,
      );
  }
  const parent = path.join(root, ".local-verification/package smoke");
  fs.mkdirSync(parent, { recursive: true });
  const extraction = fs.mkdtempSync(path.join(parent, "run-"));
  execFileSync(jdk.jar, ["xf", archive], {
    cwd: extraction,
    windowsHide: true,
  });
  const appDir = path.join(extraction, packageName);
  for (const file of allowed)
    assert(
      fs.existsSync(path.join(appDir, file)),
      `Required payload missing: ${file}`,
    );
  const payloadFiles = names
    .filter((name) => !name.endsWith("/"))
    .map((name) => name.slice(packageName.length + 1));
  const verifiedFiles = new Set();
  for (const line of fs
    .readFileSync(path.join(appDir, "SHA256SUMS"), "utf8")
    .trim()
    .split(/\r?\n/)) {
    const match = line.match(/^([0-9a-f]{64})  (.+)$/);
    assert(
      match &&
        !match[2].split("/").includes("..") &&
        !path.isAbsolute(match[2]) &&
        payloadFiles.includes(match[2]) &&
        !verifiedFiles.has(match[2]),
      "Malformed payload checksum manifest.",
    );
    assert(
      hash(path.join(appDir, match[2])) === match[1],
      `Payload checksum mismatch: ${match[2]}`,
    );
    verifiedFiles.add(match[2]);
  }
  assert(
    payloadFiles.every(
      (file) => file === "SHA256SUMS" || verifiedFiles.has(file),
    ),
    "Checksum manifest does not cover the complete payload.",
  );
  const info = JSON.parse(
    fs.readFileSync(path.join(appDir, "build-info.json"), "utf8"),
  );
  assert(
    hash(path.join(appDir, "lib/zeus-charge-control.jar")) === info.jarSha256,
    "Build metadata/JAR mismatch.",
  );
  execFileSync(
    bash,
    ["-n", path.join(appDir, "start.sh").replace(/\\/g, "/")],
    { windowsHide: true },
  );
  console.log(
    "PASS archive allowlist, payload SHA-256 checks, metadata and shell syntax",
  );
  const kinds = windows ? ["windows", "bash"] : ["bash"];
  for (const kind of kinds) await expectMissingConfig(kind, appDir, jdk, bash);
  fs.copyFileSync(
    path.join(appDir, "config/application.properties.example"),
    path.join(appDir, "config/application.properties"),
  );
  // Reproduce an older operator configuration that knows only the standard bundle.
  fs.appendFileSync(
    path.join(appDir, "config/application.properties"),
    "\nspring.messages.basename=messages\n",
  );
  const priceServer = http.createServer((request, response) => {
    const query = new URL(request.url, "http://127.0.0.1");
    const base = Math.max(
      Number(query.searchParams.get("start")) || 0,
      Math.floor(Date.now() / 3600000) * 3600000,
    );
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(
      JSON.stringify({
        object: "list",
        data: Array.from({ length: 6 }, (_, i) => ({
          start_timestamp: base + i * 3600000,
          end_timestamp: base + (i + 1) * 3600000,
          marketprice: 80 + i,
          unit: "Eur/MWh",
        })),
      }),
    );
  });
  await new Promise((resolve) => priceServer.listen(0, "127.0.0.1", resolve));
  try {
    for (const kind of kinds)
      await checkRunningPackage(
        kind,
        appDir,
        jdk,
        bash,
        priceServer.address().port,
      );
    // Modify only the disposable extracted copy after verifying the original payload.
    // The direct java -jar run ignores external config and loads this replacement.
    const jarPath = path.join(appDir, "lib/zeus-charge-control.jar");
    const replacementDir = path.join(extraction, "replacement");
    fs.mkdirSync(replacementDir);
    execFileSync(
      jdk.jar,
      ["xf", jarPath, "BOOT-INF/classes/application.properties"],
      {
        cwd: replacementDir,
        windowsHide: true,
      },
    );
    const replacementFile = path.join(
      replacementDir,
      "BOOT-INF/classes/application.properties",
    );
    const config = fs
      .readFileSync(replacementFile, "utf8")
      .replace(/^spring\.messages\..*$/gm, "");
    fs.writeFileSync(
      replacementFile,
      config +
        [
          "",
          "spring.messages.basename=messages",
          "spring.h2.console.enabled=false",
          "spring.datasource.url=jdbc:h2:file:./data/standalone-jar;DB_CLOSE_ON_EXIT=FALSE",
          "spring.jpa.hibernate.ddl-auto=update",
          "",
        ].join("\n"),
    );
    execFileSync(
      jdk.jar,
      [
        "uf",
        jarPath,
        "-C",
        replacementDir,
        "BOOT-INF/classes/application.properties",
      ],
      {
        windowsHide: true,
      },
    );
    await checkRunningPackage(
      "jar",
      appDir,
      jdk,
      bash,
      priceServer.address().port,
    );
    console.log(
      "PASS direct java -jar with replaced internal application.properties and legacy message settings",
    );
  } finally {
    await new Promise((resolve) => priceServer.close(resolve));
  }
  console.log(
    `PASS extracted distribution in a path containing spaces: ${appDir}`,
  );
})().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
