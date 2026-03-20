package com.example.expensetracker;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class ExpenseTrackerServer {

    private static final int PORT = 8080;
    private static final DataStore DATA_STORE = new DataStore("data/expense-tracker.db");
    private static final Map<String, String> SESSIONS = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", new Router());
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Expense tracker server running at http://localhost:" + PORT);
    }

    private static class Router implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/") || path.isEmpty()) {
                    redirect(exchange, currentUsername(exchange) == null ? "/login" : "/expenses");
                    return;
                }
                switch (path) {
                    case "/login" -> { handleLogin(exchange); return; }
                    case "/signup" -> { handleSignup(exchange); return; }
                    case "/logout" -> { handleLogout(exchange); return; }
                    case "/expenses" -> { requireAuth(exchange); handleDashboard(exchange); return; }
                    case "/expenses/add" -> { requireAuth(exchange); handleAddExpense(exchange); return; }
                    default -> {
                        if (path.matches("/expenses/\\d+/edit")) {
                            requireAuth(exchange);
                            handleEditExpense(exchange);
                            return;
                        }
                        if (path.matches("/expenses/\\d+/delete")) {
                            requireAuth(exchange);
                            handleDeleteExpense(exchange);
                            return;
                        }
                    }
                }
                sendResponse(exchange, 404, layout("Not Found", "<div class='auth-card'><h1>404</h1><p>Page not found.</p></div>"));
            } catch (UnauthorizedException unauthorized) {
                redirect(exchange, "/login");
            } catch (Exception exception) {
                exception.printStackTrace();
                sendResponse(exchange, 500, layout("Error", "<div class='auth-card'><h1>Server Error</h1><p>" + escapeHtml(exception.getMessage()) + "</p></div>"));
            } finally {
                exchange.close();
            }
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String message = queryParams(exchange).getOrDefault("message", "");
            String body = authCard("Login", "If you already have an account, log in here.", message,
                    "<form method='post' action='/login' class='stacked-form'>"
                            + input("Username", "username", "text", "", true)
                            + input("Password", "password", "password", "", true)
                            + "<button type='submit'>Login</button>"
                            + "</form>"
                            + "<p class='switch-link'>No account yet? <a href='/signup'>Go to signup</a></p>");
            sendResponse(exchange, 200, layout("Login", body));
            return;
        }
        Map<String, String> form = formParams(exchange);
        if (DATA_STORE.validateLogin(form.getOrDefault("username", "").trim(), form.getOrDefault("password", ""))) {
            String sessionId = UUID.randomUUID().toString();
            SESSIONS.put(sessionId, form.getOrDefault("username", "").trim());
            exchange.getResponseHeaders().add("Set-Cookie", "SESSION_ID=" + sessionId + "; Path=/; HttpOnly");
            redirect(exchange, "/expenses");
        } else {
            redirect(exchange, "/login?message=" + urlEncode("Invalid username or password."));
        }
    }

    private static void handleSignup(HttpExchange exchange) throws IOException {
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            String message = queryParams(exchange).getOrDefault("message", "");
            String body = authCard("Sign Up", "Create your account before tracking expenses.", message,
                    "<form method='post' action='/signup' class='stacked-form'>"
                            + input("Username", "username", "text", "", true)
                            + input("Password", "password", "password", "", true)
                            + input("Confirm Password", "confirmPassword", "password", "", true)
                            + "<button type='submit'>Create Account</button>"
                            + "</form>"
                            + "<p class='switch-link'>Already registered? <a href='/login'>Go to login</a></p>");
            sendResponse(exchange, 200, layout("Sign Up", body));
            return;
        }
        Map<String, String> form = formParams(exchange);
        String error = DATA_STORE.register(form.get("username"), form.get("password"), form.get("confirmPassword"));
        redirect(exchange, error == null
                ? "/login?message=" + urlEncode("Signup successful. Please log in.")
                : "/signup?message=" + urlEncode(error));
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        String sessionId = readCookie(exchange, "SESSION_ID");
        if (sessionId != null) {
            SESSIONS.remove(sessionId);
        }
        exchange.getResponseHeaders().add("Set-Cookie", "SESSION_ID=deleted; Path=/; Max-Age=0");
        redirect(exchange, "/login?message=" + urlEncode("You have been logged out."));
    }

    private static void handleDashboard(HttpExchange exchange) throws IOException {
        String username = currentUsername(exchange);
        Map<String, String> query = queryParams(exchange);
        String message = query.getOrDefault("message", "");
        String editingId = query.getOrDefault("edit", "");
        Expense editingExpense = null;
        if (!editingId.isBlank()) {
            try { editingExpense = DATA_STORE.findExpense(username, Long.parseLong(editingId)); } catch (Exception ignored) { }
        }
        LocalDate startDate = parseDate(query.get("startDate"));
        LocalDate endDate = parseDate(query.get("endDate"));
        String summaryMessage = "";
        BigDecimal total = BigDecimal.ZERO;
        if (startDate != null && endDate != null) {
            if (endDate.isBefore(startDate)) {
                summaryMessage = "End date must be after or equal to start date.";
            } else {
                total = DATA_STORE.totalBetween(username, startDate, endDate);
            }
        }
        String body = dashboardHtml(username, DATA_STORE.findExpenses(username), editingExpense, startDate, endDate, total, message, summaryMessage);
        sendResponse(exchange, 200, layout("Dashboard", body));
    }

    private static void handleAddExpense(HttpExchange exchange) throws IOException {
        ensurePost(exchange);
        String username = currentUsername(exchange);
        Map<String, String> form = formParams(exchange);
        String error = DATA_STORE.addExpense(username, form.get("title"), form.get("description"), form.get("amount"), form.get("expenseDate"));
        redirect(exchange, dashboardRedirect(form, error == null ? "Expense saved successfully." : error));
    }

    private static void handleEditExpense(HttpExchange exchange) throws IOException {
        ensurePost(exchange);
        String username = currentUsername(exchange);
        long expenseId = Long.parseLong(exchange.getRequestURI().getPath().split("/")[2]);
        Map<String, String> form = formParams(exchange);
        String error = DATA_STORE.updateExpense(username, expenseId, form.get("title"), form.get("description"), form.get("amount"), form.get("expenseDate"));
        redirect(exchange, dashboardRedirect(form, error == null ? "Expense updated successfully." : error));
    }

    private static void handleDeleteExpense(HttpExchange exchange) throws IOException {
        ensurePost(exchange);
        String username = currentUsername(exchange);
        long expenseId = Long.parseLong(exchange.getRequestURI().getPath().split("/")[2]);
        Map<String, String> form = formParams(exchange);
        DATA_STORE.deleteExpense(username, expenseId);
        redirect(exchange, dashboardRedirect(form, "Expense deleted successfully."));
    }

    private static String dashboardHtml(String username, List<Expense> expenses, Expense editingExpense,
                                        LocalDate startDate, LocalDate endDate, BigDecimal total,
                                        String message, String summaryMessage) {
        String expenseAction = editingExpense == null ? "/expenses/add" : "/expenses/" + editingExpense.id() + "/edit";
        String expenseTitle = editingExpense == null ? "Add Expense" : "Edit Expense";
        String cancelHref = "/expenses" + rangeQuery(startDate, endDate, true);
        String rows = expenses.isEmpty() ? "<tr><td colspan='5'>No expenses recorded yet.</td></tr>" : expenses.stream().map(expense ->
                "<tr><td>" + escapeHtml(expense.expenseDate().toString()) + "</td>"
                        + "<td>" + escapeHtml(expense.title()) + "</td>"
                        + "<td>" + escapeHtml(expense.description().isBlank() ? "-" : expense.description()) + "</td>"
                        + "<td>₹ " + expense.amount() + "</td>"
                        + "<td><div class='button-row'>"
                        + "<a class='secondary-link' href='/expenses?edit=" + expense.id() + rangeQuery(startDate, endDate, false) + "'>Edit</a>"
                        + "<form method='post' action='/expenses/" + expense.id() + "/delete'>" + hiddenDateFields(startDate, endDate)
                        + "<button class='danger-btn' type='submit'>Delete</button></form>"
                        + "</div></td></tr>").collect(Collectors.joining());

        return "<div class='page-shell'>"
                + "<header class='topbar'><div><h1>Daily Expense Tracker</h1><p>Welcome, " + escapeHtml(username) + ". Add, edit, delete, and summarize expenses by date range.</p></div>"
                + "<form method='post' action='/logout'><button class='secondary-btn' type='submit'>Logout</button></form></header>"
                + (message.isBlank() ? "" : "<div class='alert info'>" + escapeHtml(message) + "</div>")
                + "<section class='panel grid two-columns'><div><h2>" + expenseTitle + "</h2>"
                + "<form method='post' action='" + expenseAction + "' class='stacked-form'>" + hiddenDateFields(startDate, endDate)
                + input("Title", "title", "text", editingExpense == null ? "" : editingExpense.title(), true)
                + textarea("Description", "description", editingExpense == null ? "" : editingExpense.description())
                + input("Amount", "amount", "number", editingExpense == null ? "" : editingExpense.amount().toString(), true)
                + input("Expense Date", "expenseDate", "date", editingExpense == null ? "" : editingExpense.expenseDate().toString(), true)
                + "<div class='button-row'><button type='submit'>" + expenseTitle + "</button>"
                + (editingExpense == null ? "" : "<a class='secondary-link' href='" + cancelHref + "'>Cancel</a>")
                + "</div></form></div>"
                + "<div><h2>Total by Date Range</h2><form method='get' action='/expenses' class='stacked-form'>"
                + input("From Date", "startDate", "date", startDate == null ? "" : startDate.toString(), false)
                + input("To Date", "endDate", "date", endDate == null ? "" : endDate.toString(), false)
                + "<button type='submit'>Calculate Total</button></form>"
                + (summaryMessage.isBlank() ? "" : "<div class='alert error'>" + escapeHtml(summaryMessage) + "</div>")
                + "<div class='summary-box'><span>Total between selected dates</span><strong>₹ " + total + "</strong></div></div></section>"
                + "<section class='panel'><h2>All Expenses</h2><div class='table-wrapper'><table><thead><tr><th>Date</th><th>Title</th><th>Description</th><th>Amount</th><th>Actions</th></tr></thead><tbody>"
                + rows + "</tbody></table></div></section></div>";
    }

    private static String authCard(String title, String subtitle, String message, String formHtml) {
        return "<div class='auth-shell'><div class='auth-card'><h1>" + title + "</h1><p class='subtitle'>" + subtitle + "</p>"
                + (message.isBlank() ? "" : "<div class='alert info'>" + escapeHtml(message) + "</div>")
                + formHtml + "</div></div>";
    }

    private static String layout(String title, String body) {
        return "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'><title>" + title + " | Expense Tracker</title><style>" + Styles.CSS + "</style></head><body>" + body + "</body></html>";
    }

    private static String input(String label, String name, String type, String value, boolean required) {
        String step = "number".equals(type) ? " step='0.01' min='0.01'" : "";
        return "<label>" + label + "<input type='" + type + "' name='" + name + "' value='" + escapeHtml(value) + "'" + step + (required ? " required" : "") + "></label>";
    }

    private static String textarea(String label, String name, String value) {
        return "<label>" + label + "<textarea name='" + name + "' rows='3'>" + escapeHtml(value) + "</textarea></label>";
    }

    private static String hiddenDateFields(LocalDate startDate, LocalDate endDate) {
        return "<input type='hidden' name='startDate' value='" + (startDate == null ? "" : startDate) + "'><input type='hidden' name='endDate' value='" + (endDate == null ? "" : endDate) + "'>";
    }

    private static String rangeQuery(LocalDate startDate, LocalDate endDate, boolean first) {
        StringBuilder builder = new StringBuilder();
        if (startDate != null) {
            builder.append(first ? "?" : "&").append("startDate=").append(startDate);
            first = false;
        }
        if (endDate != null) {
            builder.append(first ? "?" : "&").append("endDate=").append(endDate);
        }
        return builder.toString();
    }

    private static String dashboardRedirect(Map<String, String> form, String message) {
        StringBuilder builder = new StringBuilder("/expenses?message=").append(urlEncode(message));
        String startDate = form.getOrDefault("startDate", "");
        String endDate = form.getOrDefault("endDate", "");
        if (!startDate.isBlank()) builder.append("&startDate=").append(urlEncode(startDate));
        if (!endDate.isBlank()) builder.append("&endDate=").append(urlEncode(endDate));
        return builder.toString();
    }

    private static void ensurePost(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) throw new IllegalArgumentException("Method not allowed");
    }

    private static void requireAuth(HttpExchange exchange) {
        if (currentUsername(exchange) == null) throw new UnauthorizedException();
    }

    private static String currentUsername(HttpExchange exchange) {
        String sessionId = readCookie(exchange, "SESSION_ID");
        return sessionId == null ? null : SESSIONS.get(sessionId);
    }

    private static String readCookie(HttpExchange exchange, String name) {
        for (String cookieHeader : exchange.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String cookie : cookieHeader.split(";")) {
                String[] parts = cookie.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(name)) return parts[1];
            }
        }
        return null;
    }

    private static Map<String, String> queryParams(HttpExchange exchange) {
        return splitParams(exchange.getRequestURI().getRawQuery());
    }

    private static Map<String, String> formParams(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return splitParams(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> splitParams(String raw) {
        Map<String, String> params = new HashMap<>();
        if (raw == null || raw.isBlank()) return params;
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            params.put(urlDecode(parts[0]), parts.length > 1 ? urlDecode(parts[1]) : "");
        }
        return params;
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException exception) { return null; }
    }

    private static String urlDecode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }
    private static String urlEncode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static class UnauthorizedException extends RuntimeException { }

    private static final class Styles {
        private static final String CSS = """
            :root { --bg:#f5f7fb; --panel:#fff; --primary:#2563eb; --primary-dark:#1d4ed8; --text:#1f2937; --muted:#6b7280; --border:#dbe4f0; --danger:#dc2626; }
            * { box-sizing:border-box; }
            body { margin:0; font-family:Arial,sans-serif; color:var(--text); background:linear-gradient(135deg,#eef4ff,var(--bg)); }
            .auth-shell,.page-shell { min-height:100vh; padding:32px 16px; }
            .auth-shell { display:grid; place-items:center; }
            .page-shell { max-width:1180px; margin:0 auto; }
            .auth-card,.panel { background:var(--panel); border-radius:18px; box-shadow:0 15px 35px rgba(37,99,235,.12); border:1px solid var(--border); }
            .auth-card { width:min(420px,100%); padding:32px; }
            .panel { padding:24px; margin-bottom:20px; }
            .topbar { display:flex; justify-content:space-between; align-items:center; gap:16px; margin-bottom:20px; }
            .grid.two-columns { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:24px; }
            .subtitle,.topbar p { color:var(--muted); }
            .stacked-form { display:flex; flex-direction:column; gap:14px; }
            label { display:flex; flex-direction:column; gap:6px; font-weight:600; }
            input,textarea,button,a { font:inherit; }
            input,textarea { width:100%; padding:12px 14px; border:1px solid var(--border); border-radius:10px; }
            button,.secondary-link { display:inline-flex; justify-content:center; align-items:center; padding:12px 16px; border-radius:10px; border:none; background:var(--primary); color:#fff; text-decoration:none; cursor:pointer; }
            button:hover,.secondary-link:hover { background:var(--primary-dark); }
            .secondary-btn,.secondary-link { background:#e5edff; color:var(--primary-dark); }
            .danger-btn { background:#fee2e2; color:var(--danger); }
            .button-row { display:flex; gap:10px; flex-wrap:wrap; }
            .alert { padding:12px 14px; border-radius:10px; margin-bottom:16px; }
            .alert.info { background:#dbeafe; color:#1d4ed8; }
            .alert.error { background:#fee2e2; color:var(--danger); }
            .summary-box { margin-top:18px; background:#eff6ff; border:1px dashed #93c5fd; border-radius:14px; padding:20px; display:flex; flex-direction:column; gap:10px; }
            .summary-box strong { font-size:2rem; }
            .table-wrapper { overflow-x:auto; }
            table { width:100%; border-collapse:collapse; }
            th,td { padding:14px; border-bottom:1px solid var(--border); text-align:left; vertical-align:top; }
            .switch-link { margin-top:18px; text-align:center; }
            .switch-link a { color:var(--primary); }
            @media (max-width:640px) { .topbar { flex-direction:column; align-items:stretch; } .auth-card,.panel { padding:20px; } }
            """;
    }
}
