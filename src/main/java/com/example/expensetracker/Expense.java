package com.example.expensetracker;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Expense(long id, String title, String description, BigDecimal amount, LocalDate expenseDate) { }
