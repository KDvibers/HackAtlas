package com.hackatlas.web;

import java.sql.SQLException;
import org.hibernate.TransactionException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Classifies infrastructure failures using types/SQLState, never error-message contents. */
public final class DataFailures {
    private DataFailures() { }

    public static boolean unavailable(Throwable failure) {
        int depth = 0;
        for (Throwable cause = failure; cause != null && depth++ < 16; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof CannotCreateTransactionException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true;
            }
            // Hikari closes a broken connection. Hibernate's failed rollback then wraps a generic
            // closed-connection SQLException without SQLState, obscuring the original SQLSTATE 08.
            if (cause instanceof TransactionException && cause.getCause() instanceof SQLException sql
                    && sql.getClass() == SQLException.class && sql.getSQLState() == null) {
                return true;
            }
        }
        return false;
    }
}
