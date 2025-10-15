package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;

import com.comerzzia.pos.ncr.messages.Tender;

final class PendingPayment {

        private final Tender message;
        private final GiftCardContext context;
        private final String cardNumber;
        private final BigDecimal amount;

        PendingPayment(Tender message, GiftCardContext context, String cardNumber, BigDecimal amount) {
                this.message = message;
                this.context = context;
                this.cardNumber = cardNumber;
                this.amount = amount;
        }

        Tender getMessage() {
                return message;
        }

        GiftCardContext getContext() {
                return context;
        }

        String getCardNumber() {
                return cardNumber;
        }

        BigDecimal getAmount() {
                return amount;
        }
}
