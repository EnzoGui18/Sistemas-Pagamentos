"use strict";

const state = {
    clients: [],
    chargePage: 0,
    totalPages: 0,
    currentCharge: null,
    referenceDate: null,
    actionRunning: false
};

const currency = new Intl.NumberFormat("pt-BR", { style: "currency", currency: "BRL" });
const dateFormatter = new Intl.DateTimeFormat("pt-BR", { timeZone: "America/Sao_Paulo" });
const dateTimeFormatter = new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "America/Sao_Paulo"
});

const conditionLabels = {
    PENDING: "Pendente",
    OVERDUE: "Em atraso",
    PAID: "Paga",
    CANCELED: "Cancelada"
};

const eventLabels = { CREATED: "Cobrança criada", PAID: "Pagamento simulado", CANCELED: "Cobrança cancelada" };

document.addEventListener("DOMContentLoaded", () => {
    bindNavigation();
    bindDialogs();
    bindForms();
    bindActions();
    loadInitialData();
});

function bindNavigation() {
    document.querySelectorAll("[data-view-target]").forEach((button) => {
        button.addEventListener("click", () => showView(button.dataset.viewTarget));
    });
    document.querySelector(".brand").addEventListener("click", (event) => {
        event.preventDefault();
        showView("dashboard");
    });
    document.querySelector("#back-to-charges").addEventListener("click", () => showView("charges"));
}

function bindDialogs() {
    document.querySelector("#open-client-dialog").addEventListener("click", () => openDialog("client-dialog"));
    document.querySelectorAll("[data-open-charge]").forEach((button) => {
        button.addEventListener("click", async () => {
            if (state.clients.length === 0) await loadClients();
            if (state.clients.length === 0) {
                showToast("Cadastre um cliente antes de criar uma cobrança.");
                openDialog("client-dialog");
                return;
            }
            setMinimumDueDate();
            openDialog("charge-dialog");
        });
    });
    document.querySelectorAll("[data-close-dialog]").forEach((button) => {
        button.addEventListener("click", () => button.closest("dialog").close());
    });
    document.querySelectorAll("dialog").forEach((dialog) => {
        dialog.addEventListener("click", (event) => {
            if (event.target === dialog) dialog.close();
        });
    });
}

function bindForms() {
    document.querySelector("#client-form").addEventListener("submit", createClient);
    document.querySelector("#charge-form").addEventListener("submit", createCharge);
    document.querySelector("#charge-filters").addEventListener("submit", (event) => {
        event.preventDefault();
        state.chargePage = 0;
        loadCharges();
    });
}

function bindActions() {
    document.querySelector("#previous-page").addEventListener("click", () => changePage(-1));
    document.querySelector("#next-page").addEventListener("click", () => changePage(1));
    document.querySelector("#dismiss-cancellation").addEventListener("click", () => {
        document.querySelector("#confirm-dialog").close();
    });
    document.querySelector("#confirm-cancellation").addEventListener("click", cancelCurrentCharge);
}

async function loadInitialData() {
    await Promise.all([loadClients(), loadDashboard()]);
}

async function api(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: { "Content-Type": "application/json", ...(options.headers || {}) }
    });
    const type = response.headers.get("content-type") || "";
    const body = type.includes("json") ? await response.json() : null;
    if (!response.ok) {
        const error = new Error(body?.detail || "Não foi possível concluir a operação.");
        error.problem = body;
        throw error;
    }
    return body;
}

async function loadDashboard() {
    setStatus("dashboard-status", "Carregando resumo...");
    setStatus("recent-status", "Carregando cobranças...");
    try {
        const [summary, recent] = await Promise.all([
            api("/api/v1/dashboard/summary"),
            api("/api/v1/charges?page=0&size=5")
        ]);
        state.referenceDate = summary.referenceDate;
        renderSummary(summary);
        renderChargeList(document.querySelector("#recent-list"), recent.content);
        setStatus("dashboard-status", "");
        setStatus("recent-status", "");
    } catch (error) {
        setStatus("dashboard-status", error.message, true);
        setStatus("recent-status", "Não foi possível carregar as cobranças.", true);
    }
}

function renderSummary(summary) {
    const grid = document.querySelector("#summary-grid");
    grid.replaceChildren();
    const metrics = [
        ["A receber", summary.receivable, "receivable"],
        ["Pendente no prazo", summary.pending, "pending"],
        ["Em atraso", summary.overdue, "overdue"],
        ["Recebido", summary.paid, "paid"],
        ["Cancelado", summary.canceled, "canceled"]
    ];
    metrics.forEach(([label, metric, style]) => {
        const card = element("article", `metric-card ${style}`);
        card.append(
            textElement("span", "metric-label", label),
            textElement("strong", "metric-value", currency.format(metric.amount)),
            textElement("span", "metric-count", `${metric.count} ${metric.count === 1 ? "cobrança" : "cobranças"}`)
        );
        grid.append(card);
    });
    grid.hidden = false;
    document.querySelector("#reference-date").textContent = `Posição em ${formatDate(summary.referenceDate)}`;
}

async function loadClients() {
    try {
        const page = await api("/api/v1/clients?page=0&size=100");
        state.clients = page.content;
        fillClientSelect(document.querySelector("#client-filter"), "Todos os clientes");
        fillClientSelect(document.querySelector("#charge-client"), "Selecione um cliente");
    } catch (error) {
        showToast(error.message);
    }
}

function fillClientSelect(select, placeholder) {
    const previous = select.value;
    select.replaceChildren();
    const empty = document.createElement("option");
    empty.value = "";
    empty.textContent = placeholder;
    select.append(empty);
    state.clients.forEach((client) => {
        const option = document.createElement("option");
        option.value = client.id;
        option.textContent = `${client.name} · ${client.email}`;
        select.append(option);
    });
    if (state.clients.some((client) => client.id === previous)) select.value = previous;
}

async function loadCharges() {
    const list = document.querySelector("#charges-list");
    list.replaceChildren();
    setStatus("charges-status", "Carregando cobranças...");
    document.querySelector("#pagination").hidden = true;
    const params = new URLSearchParams({ page: String(state.chargePage), size: "10" });
    const condition = document.querySelector("#condition-filter").value;
    const clientId = document.querySelector("#client-filter").value;
    if (condition) params.set("condition", condition);
    if (clientId) params.set("clientId", clientId);
    try {
        const page = await api(`/api/v1/charges?${params}`);
        state.totalPages = page.totalPages;
        renderChargeList(list, page.content);
        setStatus("charges-status", "");
        renderPagination(page);
    } catch (error) {
        setStatus("charges-status", error.message, true);
    }
}

function renderChargeList(container, charges) {
    container.replaceChildren();
    if (charges.length === 0) {
        container.append(textElement("p", "empty-state", "Nenhuma cobrança por aqui."));
        return;
    }
    const header = element("div", "charge-table-head");
    ["Cobrança", "Cliente", "Vencimento", "Valor", "Situação"].forEach((label) => {
        header.append(textElement("span", "", label));
    });
    container.append(header);
    charges.forEach((charge) => {
        const row = element("article", "charge-row");
        const title = document.createElement("button");
        title.type = "button";
        title.className = "charge-link";
        title.textContent = charge.description;
        title.addEventListener("click", () => showDetail(charge.id));
        const titleWrap = element("div", "row-title");
        titleWrap.append(title);
        row.append(
            titleWrap,
            textElement("span", "row-client row-secondary", charge.client.name),
            textElement("span", "row-due row-secondary", `Vence ${formatDate(charge.dueDate)}`),
            textElement("span", "amount", currency.format(charge.amount)),
            conditionBadge(charge.condition)
        );
        container.append(row);
    });
}

function renderPagination(page) {
    const pagination = document.querySelector("#pagination");
    if (page.totalPages <= 1) {
        pagination.hidden = true;
        return;
    }
    pagination.hidden = false;
    document.querySelector("#page-label").textContent = `Página ${page.page + 1} de ${page.totalPages}`;
    document.querySelector("#previous-page").disabled = page.page === 0;
    document.querySelector("#next-page").disabled = page.page + 1 >= page.totalPages;
}

function changePage(offset) {
    const next = state.chargePage + offset;
    if (next < 0 || next >= state.totalPages) return;
    state.chargePage = next;
    loadCharges();
}

async function showDetail(id) {
    showView("detail", false);
    document.querySelector("#detail-content").hidden = true;
    setStatus("detail-status", "Carregando detalhes...");
    try {
        const [charge, events] = await Promise.all([
            api(`/api/v1/charges/${id}`),
            api(`/api/v1/charges/${id}/events`)
        ]);
        state.currentCharge = charge;
        renderDetail(charge, events);
        setStatus("detail-status", "");
        document.querySelector("#detail-content").hidden = false;
    } catch (error) {
        setStatus("detail-status", error.message, true);
    }
}

function renderDetail(charge, events) {
    document.querySelector("#detail-title").textContent = charge.description;
    document.querySelector("#detail-condition").replaceChildren(conditionBadge(charge.condition));
    renderDataList(document.querySelector("#charge-data"), [
        ["Valor", currency.format(charge.amount)],
        ["Vencimento", formatDate(charge.dueDate)],
        ["Moeda", charge.currency],
        ["Criada em", formatDateTime(charge.createdAt)]
    ]);
    renderDataList(document.querySelector("#client-data"), [
        ["Nome", charge.client.name],
        ["E-mail", charge.client.email]
    ]);
    renderEvents(events);
    renderDetailActions(charge);
}

function renderDataList(list, entries) {
    list.replaceChildren();
    entries.forEach(([label, value]) => {
        const wrapper = document.createElement("div");
        wrapper.append(textElement("dt", "", label), textElement("dd", "", value));
        list.append(wrapper);
    });
}

function renderEvents(events) {
    const list = document.querySelector("#events-list");
    list.replaceChildren();
    events.forEach((event) => {
        const item = document.createElement("li");
        const time = textElement("time", "", formatDateTime(event.occurredAt));
        time.dateTime = event.occurredAt;
        item.append(textElement("strong", "", eventLabels[event.type] || event.type), time);
        list.append(item);
    });
}

function renderDetailActions(charge) {
    const actions = document.querySelector("#detail-actions");
    actions.replaceChildren();
    if (charge.status !== "PENDING") return;
    const pay = textElement("button", "primary-button", "Simular pagamento");
    pay.type = "button";
    pay.addEventListener("click", payCurrentCharge);
    const cancel = textElement("button", "secondary-button", "Cancelar cobrança");
    cancel.type = "button";
    cancel.addEventListener("click", () => document.querySelector("#confirm-dialog").showModal());
    actions.append(cancel, pay);
}

async function payCurrentCharge() {
    if (state.actionRunning || !state.currentCharge) return;
    state.actionRunning = true;
    setActionButtonsDisabled(true);
    try {
        await api(`/api/v1/charges/${state.currentCharge.id}/payments`, {
            method: "POST",
            headers: { "Idempotency-Key": crypto.randomUUID() }
        });
        showToast("Pagamento simulado com sucesso.");
        await refreshAfterAction();
    } catch (error) {
        showToast(error.message);
    } finally {
        state.actionRunning = false;
        setActionButtonsDisabled(false);
    }
}

async function cancelCurrentCharge() {
    if (state.actionRunning || !state.currentCharge) return;
    state.actionRunning = true;
    document.querySelector("#confirm-cancellation").disabled = true;
    try {
        await api(`/api/v1/charges/${state.currentCharge.id}/cancellation`, { method: "POST" });
        document.querySelector("#confirm-dialog").close();
        showToast("Cobrança cancelada.");
        await refreshAfterAction();
    } catch (error) {
        showToast(error.message);
    } finally {
        state.actionRunning = false;
        document.querySelector("#confirm-cancellation").disabled = false;
    }
}

async function refreshAfterAction() {
    const id = state.currentCharge.id;
    await Promise.all([loadDashboard(), showDetail(id)]);
}

function setActionButtonsDisabled(disabled) {
    document.querySelectorAll("#detail-actions button").forEach((button) => { button.disabled = disabled; });
}

async function createClient(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const submit = form.querySelector("button[type='submit']");
    const errorBox = form.querySelector(".form-error");
    submit.disabled = true;
    errorBox.textContent = "";
    try {
        const data = new FormData(form);
        const client = await api("/api/v1/clients", {
            method: "POST",
            body: JSON.stringify({ name: data.get("name"), email: data.get("email") })
        });
        form.reset();
        document.querySelector("#client-dialog").close();
        await loadClients();
        document.querySelector("#charge-client").value = client.id;
        showToast("Cliente cadastrado com sucesso.");
    } catch (error) {
        errorBox.textContent = problemMessage(error);
    } finally {
        submit.disabled = false;
    }
}

async function createCharge(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const submit = form.querySelector("button[type='submit']");
    const errorBox = form.querySelector(".form-error");
    submit.disabled = true;
    errorBox.textContent = "";
    try {
        const data = new FormData(form);
        const charge = await api("/api/v1/charges", {
            method: "POST",
            body: JSON.stringify({
                clientId: data.get("clientId"),
                description: data.get("description"),
                amount: Number(data.get("amount")),
                dueDate: data.get("dueDate")
            })
        });
        form.reset();
        document.querySelector("#charge-dialog").close();
        showToast("Cobrança criada com sucesso.");
        await loadDashboard();
        await showDetail(charge.id);
    } catch (error) {
        errorBox.textContent = problemMessage(error);
    } finally {
        submit.disabled = false;
    }
}

function problemMessage(error) {
    const first = error.problem?.fieldErrors?.[0];
    return first ? first.message : error.message;
}

function showView(name, load = true) {
    document.querySelectorAll(".view").forEach((view) => { view.hidden = view.id !== `${name}-view`; });
    document.querySelectorAll(".nav-button").forEach((button) => {
        button.classList.toggle("active", button.dataset.viewTarget === name);
    });
    if (name === "dashboard" && load) loadDashboard();
    if (name === "charges" && load) loadCharges();
    window.scrollTo({ top: 0, behavior: "smooth" });
}

function openDialog(id) {
    const dialog = document.querySelector(`#${id}`);
    dialog.querySelector(".form-error")?.replaceChildren();
    dialog.showModal();
}

function setMinimumDueDate() {
    const date = state.referenceDate || new Date().toISOString().slice(0, 10);
    const input = document.querySelector("#charge-form input[name='dueDate']");
    input.min = date;
    if (!input.value) input.value = date;
}

function conditionBadge(condition) {
    return textElement("span", `badge ${condition.toLowerCase()}`, conditionLabels[condition] || condition);
}

function formatDate(value) {
    if (!value) return "—";
    const [year, month, day] = value.split("-").map(Number);
    return dateFormatter.format(new Date(Date.UTC(year, month - 1, day, 12)));
}

function formatDateTime(value) {
    return value ? dateTimeFormatter.format(new Date(value)) : "—";
}

function setStatus(id, message, isError = false) {
    const status = document.querySelector(`#${id}`);
    status.textContent = message;
    status.classList.toggle("error", isError);
}

let toastTimer;
function showToast(message) {
    const toast = document.querySelector("#toast");
    toast.textContent = message;
    toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toast.hidden = true; }, 4200);
}

function element(tag, className) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    return node;
}

function textElement(tag, className, text) {
    const node = element(tag, className);
    node.textContent = text;
    return node;
}
