package com.investimento.app.service;

import com.investimento.app.data.model.Asset;
import com.investimento.app.data.model.OperationType;
import com.investimento.app.data.model.Transaction;
import com.investimento.app.dto.CreateTransactionRequest;
import com.investimento.app.dto.TransactionDTO;
import com.investimento.app.mapper.TransactionMapper;
import com.investimento.app.repository.AssetRepository;
import com.investimento.app.repository.TransactionRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Implementação de {@link TransactionService} — valida conforme RF02 (ativo
 * existente e ativo, quantidade/preço válidos, data não futura). Não valida
 * "saldo suficiente" para venda (ex.: vender mais do que foi comprado) —
 * isso é responsabilidade do cálculo de posição (ATV-10), que já precisa
 * agregar todas as transações do ativo; duplicar a checagem aqui criaria 2
 * lugares com a mesma regra podendo divergir (ver ATV-09).
 */
public class TransactionServiceImpl implements TransactionService {

    private final TransactionRepository transactionRepository;
    private final AssetRepository assetRepository;

    public TransactionServiceImpl(TransactionRepository transactionRepository, AssetRepository assetRepository) {
        this.transactionRepository = transactionRepository;
        this.assetRepository = assetRepository;
    }

    @Override
    public TransactionDTO create(CreateTransactionRequest req) throws ValidationException {
        if (req == null) {
            throw new ValidationException("Transação inválida.");
        }
        validateAsset(req.assetId());
        validateCommon(req.operationType(), req.date(), req.quantity(), req.unitPrice());

        Transaction transaction = Transaction.builder()
                .assetId(req.assetId())
                .operationType(req.operationType())
                .date(req.date())
                .quantity(req.quantity())
                .unitPrice(req.unitPrice())
                .fees(req.fees())
                .notes(req.notes())
                .build();

        Transaction inserted = transactionRepository.insert(transaction);
        return TransactionMapper.INSTANCE.toDto(inserted);
    }

    @Override
    public void update(TransactionDTO transaction) throws ValidationException {
        if (transaction == null || transaction.id() == null) {
            throw new ValidationException("Transação inválida.");
        }
        validateAsset(transaction.assetId());
        validateCommon(transaction.operationType(), transaction.date(), transaction.quantity(), transaction.unitPrice());

        transactionRepository.update(TransactionMapper.INSTANCE.toEntity(transaction));
    }

    @Override
    public void delete(long transactionId) {
        transactionRepository.delete(transactionId);
    }

    @Override
    public List<TransactionDTO> listByAsset(long assetId) {
        return transactionRepository.listByAsset(assetId).stream()
                .map(TransactionMapper.INSTANCE::toDto)
                .toList();
    }

    @Override
    public List<TransactionDTO> listAll() {
        return transactionRepository.listAll().stream()
                .map(TransactionMapper.INSTANCE::toDto)
                .toList();
    }

    @Override
    public List<TransactionDTO> listByPeriod(LocalDate start, LocalDate end) {
        return transactionRepository.listByPeriod(start, end).stream()
                .map(TransactionMapper.INSTANCE::toDto)
                .toList();
    }

    /**
     * {@code assetId} deve existir e estar ativo ({@code is_active = 1}) —
     * checado via {@code AssetRepository.findById} antes de inserir/atualizar
     * (ATV-09).
     */
    private void validateAsset(Long assetId) throws ValidationException {
        if (assetId == null) {
            throw new ValidationException("Ativo é obrigatório.");
        }
        Optional<Asset> asset = assetRepository.findById(assetId);
        if (asset.isEmpty() || !asset.get().isActive()) {
            throw new ValidationException("Ativo não encontrado ou inativo.");
        }
    }

    private void validateCommon(OperationType operationType, LocalDate date, double quantity, double unitPrice)
            throws ValidationException {
        if (operationType == null) {
            throw new ValidationException("Tipo de operação (compra/venda) é obrigatório.");
        }
        if (quantity <= 0) {
            throw new ValidationException("Quantidade deve ser maior que zero.");
        }
        if (unitPrice < 0) {
            throw new ValidationException("Preço unitário não pode ser negativo.");
        }
        if (date == null) {
            throw new ValidationException("Data é obrigatória.");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new ValidationException("Data não pode ser futura.");
        }
    }
}
