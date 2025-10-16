package com.comerzzia.ametller.pos.ncr.actions.sale.giftcard;

import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;

public final class GiftCardContext {

        private final String paymentCode;
        private final GiftCardManager manager;
        private final MedioPagoBean medioPago;
        private final boolean requiresConfirmation;
        private final String scoTenderType;
        private final boolean autoDetected;

        public GiftCardContext(String paymentCode, GiftCardManager manager, MedioPagoBean medioPago,
                        boolean requiresConfirmation, String scoTenderType, boolean autoDetected) {
                this.paymentCode = paymentCode;
                this.manager = manager;
                this.medioPago = medioPago;
                this.requiresConfirmation = requiresConfirmation;
                this.scoTenderType = scoTenderType;
                this.autoDetected = autoDetected;
        }

        public String getPaymentCode() {
                return paymentCode;
        }

        public GiftCardManager getManager() {
                return manager;
        }

        public MedioPagoBean getMedioPago() {
                return medioPago;
        }

        public boolean requiresConfirmation() {
                return requiresConfirmation;
        }

        public String getScoTenderType() {
                return scoTenderType;
        }

        public boolean isAutoDetected() {
                return autoDetected;
        }
}
