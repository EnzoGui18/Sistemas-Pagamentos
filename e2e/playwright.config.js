const { defineConfig, devices } = require("@playwright/test");

module.exports = defineConfig({
    testDir: "./tests",
    fullyParallel: true,
    forbidOnly: Boolean(process.env.CI),
    retries: process.env.CI ? 1 : 0,
    reporter: [["list"], ["html", { open: "never" }]],
    outputDir: "test-results",
    use: {
        baseURL: process.env.BASE_URL || "http://127.0.0.1:8080",
        screenshot: "only-on-failure",
        trace: "retain-on-failure",
        video: "retain-on-failure"
    },
    projects: [
        { name: "desktop-chromium", use: { ...devices["Desktop Chrome"] } },
        { name: "mobile-chromium", use: { ...devices["Pixel 7"] } }
    ]
});
