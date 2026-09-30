const { test, expect } = require("@playwright/test");
const AxeBuilder = require("@axe-core/playwright").default;

const wcagTags = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

async function expectAccessible(page, context) {
    const result = await new AxeBuilder({ page }).withTags(wcagTags).analyze();
    const summary = result.violations
        .map((violation) => `${violation.id}: ${violation.nodes.length} occurrence(s)`)
        .join("\n");
    expect(result.violations, `${context}\n${summary}`).toEqual([]);
}

async function expectNoHorizontalOverflow(page) {
    const hasOverflow = await page.evaluate(
        () => document.documentElement.scrollWidth > document.documentElement.clientWidth
    );
    expect(hasOverflow).toBe(false);
}

test("completes the main flow with an accessible responsive interface", async ({ page }, testInfo) => {
    const unique = `${testInfo.project.name}-${Date.now()}`;
    const clientName = `E2E ${unique}`;
    const email = `${unique}@example.com`;
    const description = `Charge ${unique}`;

    await page.goto("/");
    await expect(page.locator("#dashboard-title")).toHaveText("Painel");
    await expect(page.locator("#summary-grid")).toBeVisible();
    await expectAccessible(page, "Dashboard must meet WCAG A/AA checks");
    await expectNoHorizontalOverflow(page);

    await page.locator("#open-client-dialog").click();
    const clientDialog = page.locator("#client-dialog");
    await expect(clientDialog).toBeVisible();
    await expectAccessible(page, "Client dialog must meet WCAG A/AA checks");
    await clientDialog.locator("input[name='name']").fill(clientName);
    await clientDialog.locator("input[name='email']").fill(email);
    await clientDialog.locator("button[type='submit']").click();
    await expect(clientDialog).not.toBeVisible();
    await expect(page.locator("#toast")).toContainText("Cliente cadastrado");

    await page.locator("[data-open-charge]").first().click();
    const chargeDialog = page.locator("#charge-dialog");
    await expect(chargeDialog).toBeVisible();
    await chargeDialog.locator("select[name='clientId']").selectOption({ label: `${clientName} · ${email}` });
    await chargeDialog.locator("input[name='description']").fill(description);
    await chargeDialog.locator("input[name='amount']").fill("149.90");
    await chargeDialog.locator("input[name='dueDate']").fill("2099-12-31");
    await expectAccessible(page, "Charge dialog must meet WCAG A/AA checks");
    await chargeDialog.locator("button[type='submit']").click();

    await expect(page.locator("#detail-content")).toBeVisible();
    await expect(page.locator("#detail-title")).toHaveText(description);
    await expect(page.locator("#detail-condition")).toContainText("Pendente");
    await expect(page.locator("#events-list")).toContainText("criada");
    await expectAccessible(page, "Charge detail must meet WCAG A/AA checks");
    await expectNoHorizontalOverflow(page);

    await page.getByRole("button", { name: "Simular pagamento" }).click();
    await expect(page.locator("#toast")).toContainText("Pagamento simulado com sucesso");
    await expect(page.locator("#detail-condition")).toContainText("Paga");
    await expect(page.locator("#events-list")).toContainText("Pagamento simulado");

    await testInfo.attach("final-interface", {
        body: await page.screenshot({ fullPage: true }),
        contentType: "image/png"
    });
});
