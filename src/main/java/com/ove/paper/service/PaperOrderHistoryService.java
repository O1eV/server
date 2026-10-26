package com.ove.paper.service;

import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import com.ove.paper.repository.PaperOrderRepository;
import com.ove.paper.dto.OrderHistoryResponse;
import com.ove.user.repository.UserRepository;
import com.ove.global.exception.BusinessException;
import com.ove.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PaperOrderHistoryService {
    private final PaperOrderRepository repository;
    private final UserRepository users;

    @Transactional(readOnly = true)
    public OrderHistoryResponse getOrders(UUID userId, String symbol, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        users.findById(userId).filter(user -> !user.isWithdrawn())
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("orderedAt"), Sort.Order.desc("id")));
        if (symbol == null || symbol.isBlank()) return OrderHistoryResponse.from(repository.findAllByPaperAccountUserId(userId, pageable));
        String normalized = symbol.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9-]{1,30}")) throw new BusinessException(ErrorCode.INVALID_REQUEST);
        return OrderHistoryResponse.from(repository.findAllByPaperAccountUserIdAndSymbol(userId, normalized, pageable));
    }
}
