package com.example.expensetracker;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class DataStore {
    private final Path path;
    private final List<UserRecord> users = new ArrayList<>();
    private final AtomicLong expenseIdGenerator = new AtomicLong(1L);

    public DataStore(String filePath) {
        this.path = Path.of(filePath);
        load();
    }

    public synchronized String register(String username, String password, String confirmPassword) {
        if (username == null || username.isBlank()) return "Username is required.";
        if (password == null || password.length() < 6) return "Password must be at least 6 characters.";
        if (!Objects.equals(password, confirmPassword)) return "Passwords do not match.";
        if (findUser(username.trim()) != null) return "Username already exists.";
        users.add(new UserRecord(username.trim(), password, new ArrayList<>()));
        save();
        return null;
    }

    public synchronized boolean validateLogin(String username, String password) {
        UserRecord user = findUser(username);
        return user != null && Objects.equals(user.password(), password);
    }

    public synchronized String addExpense(String username, String title, String description, String amountText, String dateText) {
        UserRecord user = requireUser(username);
        ValidationResult validation = validateExpense(title, description, amountText, dateText);
        if (validation.error() != null) return validation.error();
        user.expenses().add(new Expense(expenseIdGenerator.getAndIncrement(), validation.title(), validation.description(), validation.amount(), validation.date()));
        sortExpenses(user.expenses());
        save();
        return null;
    }

    public synchronized String updateExpense(String username, long expenseId, String title, String description, String amountText, String dateText) {
        UserRecord user = requireUser(username);
        ValidationResult validation = validateExpense(title, description, amountText, dateText);
        if (validation.error() != null) return validation.error();
        user.expenses().removeIf(expense -> expense.id() == expenseId);
        user.expenses().add(new Expense(expenseId, validation.title(), validation.description(), validation.amount(), validation.date()));
        sortExpenses(user.expenses());
        save();
        return null;
    }

    public synchronized void deleteExpense(String username, long expenseId) {
        requireUser(username).expenses().removeIf(expense -> expense.id() == expenseId);
        save();
    }

    public synchronized List<Expense> findExpenses(String username) {
        return new ArrayList<>(requireUser(username).expenses());
    }

    public synchronized Expense findExpense(String username, long expenseId) {
        return requireUser(username).expenses().stream().filter(expense -> expense.id() == expenseId).findFirst().orElseThrow(() -> new IllegalArgumentException("Expense not found."));
    }

    public synchronized BigDecimal totalBetween(String username, LocalDate startDate, LocalDate endDate) {
        return requireUser(username).expenses().stream()
                .filter(expense -> !expense.expenseDate().isBefore(startDate) && !expense.expenseDate().isAfter(endDate))
                .map(Expense::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private ValidationResult validateExpense(String title, String description, String amountText, String dateText) {
        if (title == null || title.isBlank()) return new ValidationResult("Title is required.", null, null, null, null);
        try {
            BigDecimal amount = new BigDecimal(amountText);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) return new ValidationResult("Amount must be greater than zero.", null, null, null, null);
            LocalDate date = LocalDate.parse(dateText);
            return new ValidationResult(null, title.trim(), description == null ? "" : description.trim(), amount, date);
        } catch (NumberFormatException exception) {
            return new ValidationResult("Amount must be a valid number.", null, null, null, null);
        } catch (DateTimeParseException | NullPointerException exception) {
            return new ValidationResult("Expense date is required.", null, null, null, null);
        }
    }

    private UserRecord findUser(String username) {
        if (username == null) return null;
        return users.stream().filter(user -> user.username().equals(username.trim())).findFirst().orElse(null);
    }

    private UserRecord requireUser(String username) {
        UserRecord user = findUser(username);
        if (user == null) throw new IllegalArgumentException("User not found.");
        return user;
    }

    private void sortExpenses(List<Expense> expenses) {
        expenses.sort(Comparator.comparing(Expense::expenseDate).reversed().thenComparing(Expense::id).reversed());
    }

    private void load() {
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                Files.writeString(path, "", StandardCharsets.UTF_8);
                return;
            }
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\|", -1);
                if (parts[0].equals("USER") && parts.length >= 3) {
                    users.add(new UserRecord(parts[1], parts[2], new ArrayList<>()));
                } else if (parts[0].equals("EXPENSE") && parts.length >= 7) {
                    UserRecord user = findUser(parts[1]);
                    if (user != null) {
                        long id = Long.parseLong(parts[2]);
                        user.expenses().add(new Expense(id, decode(parts[3]), decode(parts[4]), new BigDecimal(parts[5]), LocalDate.parse(parts[6])));
                        expenseIdGenerator.set(Math.max(expenseIdGenerator.get(), id + 1));
                    }
                }
            }
            users.forEach(user -> sortExpenses(user.expenses()));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load data store.", exception);
        }
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        for (UserRecord user : users) {
            lines.add("USER|" + user.username() + "|" + user.password());
            for (Expense expense : user.expenses()) {
                lines.add("EXPENSE|" + user.username() + "|" + expense.id() + "|" + encode(expense.title()) + "|" + encode(expense.description()) + "|" + expense.amount() + "|" + expense.expenseDate());
            }
        }
        try {
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to save data store.", exception);
        }
    }

    private String encode(String value) { return value.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A"); }
    private String decode(String value) { return value.replace("%0A", "\n").replace("%7C", "|").replace("%25", "%"); }

    private record UserRecord(String username, String password, List<Expense> expenses) { }
    private record ValidationResult(String error, String title, String description, BigDecimal amount, LocalDate date) { }
}
