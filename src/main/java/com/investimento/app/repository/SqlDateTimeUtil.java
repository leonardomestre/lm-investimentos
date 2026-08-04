package com.investimento.app.repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Conversao de/para o formato de TEXT usado pelas colunas *_at (created_at,
 * fetched_at) do schema.sql: "yyyy-MM-dd HH:mm:ss" (formato produzido por
 * datetime('now') do SQLite). Utilitario compartilhado por todas as
 * XxxRepositoryImpl deste pacote.
 */
final class SqlDateTimeUtil {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SqlDateTimeUtil() {
    }

    static LocalDateTime parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace('T', ' ');
        if (normalized.length() > 19) {
            normalized = normalized.substring(0, 19);
        }
        return LocalDateTime.parse(normalized, FORMAT);
    }

    static String format(LocalDateTime value) {
        return value == null ? null : value.format(FORMAT);
    }
}
