package com.investimento.app.service;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Backup local (RF08, ATV-19). O banco inteiro e 1 arquivo SQLite
 * (%APPDATA%\InvestimentoApp\investimento.db) - gerar backup e copiar esse
 * arquivo, restaurar e substitui-lo. Usado pela tela de Configuracoes
 * (ATV-18).
 */
public interface BackupService {

    /**
     * Copia o arquivo .db atual para {@code destination} (escolhido via
     * FileChooser na UI, ATV-18). Forca um checkpoint do WAL antes de
     * copiar, para nao deixar escritas recentes so no arquivo -wal auxiliar
     * de fora do backup.
     */
    void createBackup(Path destination) throws IOException;

    /**
     * Fecha a conexao ativa, substitui o arquivo .db pelo {@code backupFile}
     * escolhido, e reabre a conexao. So e seguro chamar isto enquanto
     * nenhuma outra operacao de banco esta em andamento (nenhuma Task de
     * MarketService rodando, nenhuma tela no meio de um insert/update) - a
     * UI que chama este metodo (ATV-18) precisa garantir isso antes de
     * invocar, ex.: desabilitando a tela / esperando Tasks em andamento
     * terminarem.
     */
    void restoreBackup(Path backupFile) throws IOException;

    /**
     * Apaga todos os dados do usuario (ATV-18, "zona de perigo") - executa
     * {@code DELETE FROM} em cada tabela da conexao ja aberta (assets,
     * transactions/quote_history/distributions via {@code ON DELETE CASCADE},
     * rate_history, indicator_history, portfolio_snapshot e settings).
     *
     * <p>Diferente de {@link #restoreBackup(Path)}, NAO fecha/reabre a
     * conexao (nao chama {@code Database.reopen()}) - os repositories ja
     * construidos no composition root continuam validos depois de chamar
     * isto, sem precisar reconstruir nada. Acao irreversivel - a UI (ATV-18)
     * e responsavel por exigir confirmacao explicita antes de chamar.</p>
     */
    void eraseAllData();
}
