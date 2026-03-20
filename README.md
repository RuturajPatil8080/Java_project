# Daily Expense Tracker

A lightweight Java web app for daily expense tracking with signup/login, expense CRUD, and total calculation between a start date and end date.

## Run

```bash
mkdir -p out
javac -d out src/main/java/com/example/expensetracker/*.java
java -cp out com.example.expensetracker.ExpenseTrackerServer
```

Then open `http://localhost:8080` in your browser.

## Data storage
The app stores user and expense data in `data/expense-tracker.db`.
