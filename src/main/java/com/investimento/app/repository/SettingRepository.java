package com.investimento.app.repository;

public interface SettingRepository {

    String get(String key, String defaultValue);

    void save(String key, String value);
}
