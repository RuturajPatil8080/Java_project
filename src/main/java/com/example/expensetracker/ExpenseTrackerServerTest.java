package com.example.expensetracker;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

public class ExpenseTrackerServerTest {
    public static void main(String[] args) throws Exception {
        Path tempFile = Files.createTempFile("expense-tracker-test", ".db");
        DataStore store = new DataStore(tempFile.toString());
        assert store.register("alice", "secret1", "secret1") == null;
        assert store.validateLogin("alice", "secret1");
        assert store.addExpense("alice", "Lunch", "Office meal", "12.50", "2026-03-18") == null;
        assert store.addExpense("alice", "Cab", "Airport", "25.00", "2026-03-19") == null;
        assert store.findExpenses("alice").size() == 2;
        assert store.totalBetween("alice", LocalDate.parse("2026-03-18"), LocalDate.parse("2026-03-19")).compareTo(new BigDecimal("37.50")) == 0;
        Expense expense = store.findExpenses("alice").stream().filter(item -> item.title().equals("Cab")).findFirst().orElseThrow();
        assert store.updateExpense("alice", expense.id(), "Cab", "Airport ride", "30.00", "2026-03-19") == null;
        assert store.totalBetween("alice", LocalDate.parse("2026-03-18"), LocalDate.parse("2026-03-19")).compareTo(new BigDecimal("42.50")) == 0;
        store.deleteExpense("alice", expense.id());
        assert store.findExpenses("alice").size() == 1;
        Files.deleteIfExists(tempFile);
        System.out.println("ExpenseTrackerServerTest passed");
    }
}
