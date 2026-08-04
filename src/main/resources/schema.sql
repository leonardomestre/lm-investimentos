PRAGMA foreign_keys = ON;

-- ==========================================================
-- Ativos cadastrados pelo usuário (RF01)
-- ==========================================================
CREATE TABLE IF NOT EXISTS assets (
    id                      INTEGER PRIMARY KEY AUTOINCREMENT,
    type                    TEXT NOT NULL CHECK (type IN ('STOCK','FII','ETF','BDR','FIXED_INCOME','CRYPTO','FOREIGN_CURRENCY')),
    category                TEXT NOT NULL CHECK (category IN ('STOCKS','FIIS','FIXED_INCOME','CRYPTO','FOREX')),
    ticker                  TEXT,          -- B3 (PETR4) ou símbolo cripto (BTC). NULL para renda fixa.
    display_name            TEXT NOT NULL,
    currency                TEXT NOT NULL DEFAULT 'BRL',   -- codigo ISO: BRL, USD, EUR...

    -- Roteamento de cotação (RF06 — 1 fonte fixa por ativo, nunca recalculada por tela)
    quote_source            TEXT NOT NULL CHECK (quote_source IN ('HGBRASIL','BRAPI','COINGECKO','MANUAL','NONE')),
    source_identifier       TEXT,          -- ex.: "PETR4" (brapi), "bitcoin" (coingecko id), "FOREX:USDBRL" (HG Brasil)

    -- Campos exclusivos de FIXED_INCOME (RF01: "campos condicionais conforme o tipo")
    benchmark               TEXT CHECK (benchmark IN ('CDI','SELIC','IPCA','FIXED_RATE') OR benchmark IS NULL),
    contracted_rate_pct     REAL,          -- ex.: 110 (110% do CDI) ou 12.5 (12,5% a.a. prefixado)
    financial_institution   TEXT,
    investment_date         TEXT,          -- ISO-8601 yyyy-MM-dd
    maturity_date            TEXT,          -- ISO-8601 yyyy-MM-dd

    is_active               INTEGER NOT NULL DEFAULT 1,   -- soft delete (0 = removido/oculto)
    created_at              TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_assets_category ON assets(category);
CREATE INDEX IF NOT EXISTS idx_assets_ticker ON assets(ticker);

-- ==========================================================
-- Transações de compra/venda (RF02)
-- ==========================================================
CREATE TABLE IF NOT EXISTS transactions (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    asset_id            INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    operation_type      TEXT NOT NULL CHECK (operation_type IN ('BUY','SELL')),
    date                TEXT NOT NULL,     -- ISO-8601 yyyy-MM-dd
    quantity            REAL NOT NULL CHECK (quantity > 0),
    unit_price          REAL NOT NULL CHECK (unit_price >= 0),
    fees                REAL NOT NULL DEFAULT 0,
    notes               TEXT,
    created_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_transactions_asset ON transactions(asset_id);
CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(date);

-- ==========================================================
-- Histórico de cotação por ativo cadastrado (RF03, RT01)
-- Alimentada por: seed inicial (brapi range=max / CoinGecko days=365,
-- ver ATV-06) + coleta periódica própria.
-- ==========================================================
CREATE TABLE IF NOT EXISTS quote_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    asset_id        INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    date            TEXT NOT NULL,         -- ISO-8601 yyyy-MM-dd (1 registro por dia por ativo)
    price           REAL NOT NULL,
    source          TEXT NOT NULL CHECK (source IN ('HGBRASIL','BRAPI','COINGECKO','MANUAL')),
    fetched_at      TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(asset_id, date)                 -- 1 preco por ativo por dia — upsert, nao duplicar
);

CREATE INDEX IF NOT EXISTS idx_quote_history_asset_date ON quote_history(asset_id, date);

-- ==========================================================
-- CDI/SELIC diários (RF03, RT01) — espelha /finance/taxes da HG Brasil
-- ==========================================================
CREATE TABLE IF NOT EXISTS rate_history (
    date            TEXT PRIMARY KEY,      -- ISO-8601 yyyy-MM-dd
    cdi             REAL NOT NULL,
    selic           REAL NOT NULL,
    cdi_daily       REAL,
    selic_daily     REAL,
    daily_factor    REAL,
    fetched_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ==========================================================
-- IPCA e meta SELIC (RF03, RF04) — espelha /v2/finance/indicators
-- ==========================================================
CREATE TABLE IF NOT EXISTS indicator_history (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    ticker          TEXT NOT NULL CHECK (ticker IN ('IBGE:IPCA','BCB:SELICMETA')),
    period          TEXT NOT NULL,         -- 'yyyy-MM' (IPCA) ou 'yyyy-MM-dd' (SELICMETA)
    value           REAL NOT NULL,
    fetched_at      TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE(ticker, period)
);

-- ==========================================================
-- Snapshot diário do patrimônio total (RF04 — gráfico de evolução)
-- ==========================================================
CREATE TABLE IF NOT EXISTS portfolio_snapshot (
    date                TEXT PRIMARY KEY,  -- ISO-8601 yyyy-MM-dd, 1 por dia
    total_value         REAL NOT NULL,     -- soma do valor atual de todos os ativos
    invested_value      REAL NOT NULL,     -- soma do custo de aquisição líquido
    fetched_at          TEXT NOT NULL DEFAULT (datetime('now'))
);

-- ==========================================================
-- Configurações do app (RF08, tela 5.7) — chave/valor genérico
-- ==========================================================
CREATE TABLE IF NOT EXISTS settings (
    key     TEXT PRIMARY KEY,
    value   TEXT NOT NULL
);

-- ==========================================================
-- Proventos recebidos (tela 5.3) — dividendos/JCP/rendimentos.
-- Registro MANUAL: nenhuma das 3 APIs do projeto libera esse dado no
-- plano gratuito (ver brapi-api/SKILL.md — dividends=true e
-- FEATURE_NOT_AVAILABLE). Usado pela ATV-14.
-- ==========================================================
CREATE TABLE IF NOT EXISTS distributions (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    asset_id        INTEGER NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    type            TEXT NOT NULL CHECK (type IN ('DIVIDEND','INTEREST_ON_EQUITY','INCOME')),
    payment_date    TEXT NOT NULL,      -- ISO-8601 yyyy-MM-dd
    value           REAL NOT NULL CHECK (value >= 0),
    notes           TEXT,
    created_at      TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_distributions_asset ON distributions(asset_id);
