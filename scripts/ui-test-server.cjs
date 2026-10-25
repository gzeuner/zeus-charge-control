const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");
const root = path.resolve(__dirname, "..");
const classpathFile = path.join(root, "target/ui-test-classpath.txt");
if (!fs.existsSync(classpathFile))
  throw new Error(
    "Run mvn test-compile dependency:build-classpath -Dmdep.outputFile=target/ui-test-classpath.txt first.",
  );
const classpath = [
  "target/test-classes",
  "target/classes",
  fs.readFileSync(classpathFile, "utf8").trim(),
].join(path.delimiter);
const java = process.env.JAVA_HOME
  ? path.join(
      process.env.JAVA_HOME,
      "bin",
      process.platform === "win32" ? "java.exe" : "java",
    )
  : "java";
const child = spawn(
  java,
  [
    "-XX:+EnableDynamicAgentLoading",
    "-cp",
    classpath,
    "de.zeus.power.ui.UiTestApplication",
  ],
  { cwd: root, stdio: "inherit", windowsHide: true },
);
for (const signal of ["SIGINT", "SIGTERM"])
  process.on(signal, () => child.kill());
child.on("exit", (code) => process.exit(code ?? 1));
