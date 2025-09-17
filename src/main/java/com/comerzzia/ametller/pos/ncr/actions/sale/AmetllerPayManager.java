package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.api.rest.client.movimientos.MovimientosRest;
import com.comerzzia.core.servicios.variables.Variables;
import com.comerzzia.pos.persistence.giftcard.GiftCardBean;
import com.comerzzia.pos.services.core.sesion.Sesion;
import com.comerzzia.pos.services.core.variables.VariablesServices;
import com.comerzzia.pos.services.payments.PaymentException;
import com.comerzzia.pos.services.payments.PaymentsManager;
import com.comerzzia.pos.services.payments.events.PaymentErrorEvent;
import com.comerzzia.pos.services.payments.events.PaymentOkEvent;
import com.comerzzia.pos.services.payments.events.PaymentsErrorEvent;
import com.comerzzia.pos.services.payments.methods.PaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.BasicPaymentMethodManager;
import com.comerzzia.pos.services.payments.methods.types.GiftCardManager;
import com.comerzzia.pos.services.ticket.pagos.PagoTicket;
import com.comerzzia.pos.services.ticket.pagos.tarjeta.DatosRespuestaPagoTarjeta;
import com.comerzzia.pos.util.i18n.I18N;
import com.comerzzia.pos.ncr.actions.sale.PayManager;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.ncr.messages.TenderAccepted;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;

@Service
@Primary
public class AmetllerPayManager extends PayManager {

    private static final Logger log = Logger.getLogger(AmetllerPayManager.class);
    private static final String TENDER_TYPE_GIFT_CARD = "GIFTCARD";
    private static final Set<String> AUTO_DETECTED_TENDER_TYPES = new HashSet<>(
            Arrays.asList("OTRASTARJETAS", "OTHERCARDS"));

    @Autowired
    private Sesion sesion;

    @Autowired
    private VariablesServices variablesServices;

    @Override
    protected void activateTenderMode() {
        if (ticketManager instanceof AmetllerScoTicketManager) {
            ((AmetllerScoTicketManager) ticketManager).setDescuento25Activo(false);
        }
        super.activateTenderMode();
    }

    @Override
    protected void trayPay(Tender message) {
        String tenderType = StringUtils.trimToEmpty(message.getFieldValue(Tender.TenderType));

        boolean explicitGiftCard = isGiftCardTender(tenderType);
        boolean autoDetectedGiftCard = isAutoDetectedGiftCardTender(tenderType);

        if (explicitGiftCard || autoDetectedGiftCard) {
            if (processGiftCardTender(message, explicitGiftCard)) {
                return;
            }
        }

        super.trayPay(message);
    }

    private boolean isGiftCardTender(String tenderType) {
        if (StringUtils.isBlank(tenderType)) {
            return false;
        }

        return TENDER_TYPE_GIFT_CARD.equalsIgnoreCase(tenderType.replace(" ", ""));
    }

    private boolean isAutoDetectedGiftCardTender(String tenderType) {
        if (StringUtils.isBlank(tenderType)) {
            return false;
        }

        String normalized = tenderType.replaceAll("[\\s_]", "").toUpperCase(Locale.ROOT);
        return AUTO_DETECTED_TENDER_TYPES.contains(normalized);
    }

    private boolean processGiftCardTender(Tender message, boolean explicitSelection) {
        if (log.isDebugEnabled()) {
            log.debug("processGiftCardTender() - explicitSelection=" + explicitSelection);
        }

        String numeroTarjeta = StringUtils.trimToEmpty(message.getFieldValue(Tender.UPC));

        if (StringUtils.isBlank(numeroTarjeta)) {
            log.warn("processGiftCardTender() - Número de tarjeta vacío");
            sendGiftCardError(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."));
            return true;
        }

        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();
        if (paymentsManager == null) {
            log.error("processGiftCardTender() - Payments manager not available");
            sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."));
            return true;
        }

        Map.Entry<String, PaymentMethodManager> entry = findGiftCardManager(paymentsManager);

        if (entry == null) {
            log.error("processGiftCardTender() - Medio de pago de tarjeta regalo no configurado");
            sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."));
            return true;
        }

        PaymentMethodManager paymentMethodManager = entry.getValue();
        boolean waitStateSent = false;

        try {
            ncrController.sendWaitState(I18N.getTexto("Validando tarjeta regalo..."));
            waitStateSent = true;

            GiftCardBean giftCard = loadGiftCard(numeroTarjeta);

            normalizeGiftCard(giftCard, numeroTarjeta);

            BigDecimal amountToCharge = resolveGiftCardAmount(message, giftCard);

            updateGiftCardAmount(giftCard, amountToCharge);

            paymentMethodManager.addParameter(GiftCardManager.PARAM_TARJETA, giftCard);

            message.setFieldIntValue(Tender.Amount, amountToCharge);

            paymentsManager.pay(entry.getKey(), amountToCharge);
            return true;
        }
        catch (GiftCardProcessingException e) {
            log.error("processGiftCardTender() - " + e.getMessage(), e);
            handleGiftCardError(paymentsManager, paymentMethodManager, e, e.getMessage());
        }
        catch (Exception e) {
            log.error("processGiftCardTender() - Error inesperado: " + e.getMessage(), e);
            handleGiftCardError(paymentsManager, paymentMethodManager, e, e.getMessage());
        }
        finally {
            if (waitStateSent) {
                ncrController.sendFinishWaitState();
            }
        }

        return true;
    }

    private Map.Entry<String, PaymentMethodManager> findGiftCardManager(PaymentsManager paymentsManager) {
        for (Map.Entry<String, PaymentMethodManager> entry : paymentsManager.getPaymentsMehtodManagerAvailables().entrySet()) {
            if (entry.getValue() instanceof GiftCardManager) {
                return entry;
            }
        }
        return null;
    }

    private GiftCardBean loadGiftCard(String numeroTarjeta) throws GiftCardProcessingException {
        Method method = findGiftCardConsultMethod();

        if (method == null) {
            throw new GiftCardProcessingException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
        }

        try {
            Object response = invokeGiftCardMethod(method, numeroTarjeta);

            if (response == null) {
                throw new GiftCardProcessingException(I18N.getTexto("No se ha encontrado información para la tarjeta regalo."));
            }

            if (!(response instanceof GiftCardBean)) {
                throw new GiftCardProcessingException(I18N.getTexto("Respuesta inválida al consultar la tarjeta regalo."));
            }

            return (GiftCardBean) response;
        }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            String message = cause != null ? cause.getMessage() : e.getMessage();
            throw new GiftCardProcessingException(I18N.getTexto("Ha habido un error al validar la tarjeta regalo: {0}", message));
        }
        catch (IllegalArgumentException e) {
            throw new GiftCardProcessingException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
        }
        catch (IllegalAccessException e) {
            throw new GiftCardProcessingException(I18N.getTexto("No se ha podido acceder al servicio de tarjetas regalo."));
        }
    }

    private Object invokeGiftCardMethod(Method method, String numeroTarjeta) throws IllegalAccessException, InvocationTargetException, GiftCardProcessingException {
        List<Object[]> candidates = buildGiftCardArguments(numeroTarjeta);
        Class<?>[] parameterTypes = method.getParameterTypes();
        InvocationTargetException lastInvocationException = null;
        IllegalArgumentException lastIllegalArgument = null;

        for (Object[] candidate : candidates) {
            if (candidate == null || candidate.length != parameterTypes.length) {
                continue;
            }

            boolean compatible = true;

            for (int i = 0; i < parameterTypes.length; i++) {
                if (candidate[i] == null || !parameterTypes[i].isInstance(candidate[i])) {
                    compatible = false;
                    break;
                }
            }

            if (!compatible) {
                continue;
            }

            try {
                return method.invoke(null, candidate);
            }
            catch (IllegalArgumentException e) {
                lastIllegalArgument = e;
            }
            catch (InvocationTargetException e) {
                lastInvocationException = e;
            }
        }

        if (lastInvocationException != null) {
            throw lastInvocationException;
        }

        if (lastIllegalArgument != null) {
            throw lastIllegalArgument;
        }

        throw new GiftCardProcessingException(I18N.getTexto("No se ha podido consultar el saldo de la tarjeta regalo."));
    }

    private List<Object[]> buildGiftCardArguments(String numeroTarjeta) {
        List<Object[]> arguments = new ArrayList<>();

        addArgumentCombination(arguments, numeroTarjeta);

        String uidActividad = getUidActividad();
        String apiKey = getApiKey();
        String codAlmacen = getCodAlmacen();
        String codCaja = getCodCaja();
        String uidInstancia = getUidInstancia();

        addArgumentCombination(arguments, uidActividad, numeroTarjeta);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad);

        addArgumentCombination(arguments, uidActividad, numeroTarjeta, apiKey);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad, apiKey);

        addArgumentCombination(arguments, uidActividad, uidInstancia, numeroTarjeta);
        addArgumentCombination(arguments, uidActividad, numeroTarjeta, uidInstancia);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad, uidInstancia);

        addArgumentCombination(arguments, uidActividad, uidInstancia, numeroTarjeta, apiKey);
        addArgumentCombination(arguments, uidActividad, numeroTarjeta, uidInstancia, apiKey);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad, uidInstancia, apiKey);

        addArgumentCombination(arguments, uidActividad, codAlmacen, codCaja, numeroTarjeta);
        addArgumentCombination(arguments, uidActividad, numeroTarjeta, codAlmacen, codCaja);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad, codAlmacen, codCaja);

        addArgumentCombination(arguments, uidActividad, codAlmacen, codCaja, numeroTarjeta, apiKey);
        addArgumentCombination(arguments, uidActividad, numeroTarjeta, codAlmacen, codCaja, apiKey);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad, codAlmacen, codCaja, apiKey);

        addArgumentCombination(arguments, uidActividad, uidInstancia, codAlmacen, codCaja, numeroTarjeta);
        addArgumentCombination(arguments, uidActividad, codAlmacen, codCaja, uidInstancia, numeroTarjeta);
        addArgumentCombination(arguments, uidActividad, uidInstancia, codAlmacen, codCaja, numeroTarjeta, apiKey);
        addArgumentCombination(arguments, uidActividad, codAlmacen, codCaja, uidInstancia, numeroTarjeta, apiKey);
        addArgumentCombination(arguments, numeroTarjeta, uidActividad, uidInstancia, codAlmacen, codCaja, apiKey);

        return arguments;
    }

    private Method findGiftCardConsultMethod() {
        for (Method method : MovimientosRest.class.getMethods()) {
            if (!GiftCardBean.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }

            String name = method.getName().toLowerCase(Locale.ROOT);

            if (name.contains("tarjetaregalo") && (name.contains("consult") || name.contains("obten") || name.contains("saldo") || name.contains("buscar"))) {
                return method;
            }
        }

        return null;
    }

    private BigDecimal resolveGiftCardAmount(Tender message, GiftCardBean giftCard) throws GiftCardProcessingException {
        if (giftCard == null) {
            throw new GiftCardProcessingException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
        }

        BigDecimal pending = ticketManager.getTicket().getTotales().getPendiente();
        BigDecimal saldo = giftCard.getSaldo();

        BigDecimal requested = parseAmount(message.getFieldValue(Tender.Amount));

        if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0) {
            requested = pending;
        }

        BigDecimal amount = requested;

        if (pending != null && amount != null && amount.compareTo(pending) > 0) {
            amount = pending;
        }

        if (saldo == null) {
            saldo = BigDecimal.ZERO;
        }

        if (amount == null) {
            amount = saldo;
        }

        if (amount.compareTo(saldo) > 0) {
            amount = saldo;
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new GiftCardProcessingException(I18N.getTexto("El saldo de la tarjeta regalo no es suficiente."));
        }

        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal parseAmount(String amountField) {
        if (StringUtils.isBlank(amountField)) {
            return null;
        }

        try {
            return new BigDecimal(amountField).divide(new BigDecimal(100));
        }
        catch (NumberFormatException e) {
            log.warn("parseAmount() - Formato de importe inválido: " + amountField, e);
            return null;
        }
    }

    private void handleGiftCardError(PaymentsManager paymentsManager, PaymentMethodManager paymentMethodManager,
            Exception exception, String errorMessage) {
        String message = StringUtils.defaultIfBlank(errorMessage,
                I18N.getTexto("Ha habido un error al procesar el pago con tarjeta regalo."));

        sendGiftCardError(message);

        if (paymentsManager == null) {
            return;
        }

        Object source = paymentMethodManager != null ? paymentMethodManager : this;

        PaymentErrorEvent errorEvent;

        if (exception instanceof PaymentException) {
            PaymentException paymentException = (PaymentException) exception;
            errorEvent = new PaymentErrorEvent(source, paymentException.getPaymentId(), exception,
                    paymentException.getErrorCode(), message);
        }
        else {
            errorEvent = new PaymentErrorEvent(source, -1, exception, null, message);
        }

        PaymentsErrorEvent event = new PaymentsErrorEvent(source, errorEvent);
        paymentsManager.getEventsHandler().paymentsError(event);
    }

    private void sendGiftCardError(String message) {
        TenderException tenderException = new TenderException();
        tenderException.setFieldValue(TenderException.ExceptionId, "0");
        tenderException.setFieldValue(TenderException.ExceptionType, "0");
        tenderException.setFieldValue(TenderException.TenderType, "Gift Card");
        tenderException.setFieldValue(TenderException.Message, message);

        ncrController.sendMessage(tenderException);
    }

    private void addArgumentCombination(List<Object[]> arguments, Object... values) {
        if (values == null || values.length == 0) {
            return;
        }

        for (Object value : values) {
            if (value == null) {
                return;
            }

            if (value instanceof String && StringUtils.isBlank((String) value)) {
                return;
            }
        }

        arguments.add(values);
    }

    private void normalizeGiftCard(GiftCardBean giftCard, String numeroTarjeta) {
        if (giftCard == null) {
            return;
        }

        if (StringUtils.isBlank(giftCard.getNumTarjetaRegalo())) {
            giftCard.setNumTarjetaRegalo(numeroTarjeta);
        }

        BigDecimal saldo = giftCard.getSaldo();
        BigDecimal saldoProvisional = giftCard.getSaldoProvisional();

        if (saldo == null) {
            saldo = BigDecimal.ZERO;
            giftCard.setSaldo(saldo);
        }

        if (saldoProvisional == null) {
            saldoProvisional = BigDecimal.ZERO;
            giftCard.setSaldoProvisional(saldoProvisional);
        }
    }

    private void updateGiftCardAmount(GiftCardBean giftCard, BigDecimal amount) {
        if (giftCard == null || amount == null) {
            return;
        }

        giftCard.setImportePago(amount);
    }

    private String getUidActividad() {
        return sesion != null && sesion.getAplicacion() != null ? sesion.getAplicacion().getUidActividad() : null;
    }

    private String getUidInstancia() {
        return sesion != null && sesion.getAplicacion() != null ? sesion.getAplicacion().getUidInstancia() : null;
    }

    private String getCodAlmacen() {
        return sesion != null && sesion.getAplicacion() != null ? sesion.getAplicacion().getCodAlmacen() : null;
    }

    private String getCodCaja() {
        return sesion != null && sesion.getAplicacion() != null ? sesion.getAplicacion().getCodCaja() : null;
    }

    private String getApiKey() {
        try {
            return variablesServices != null ? variablesServices.getVariableAsString(Variables.WEBSERVICES_APIKEY) : null;
        }
        catch (Exception e) {
            log.warn("getApiKey() - No se ha podido obtener la API key: " + e.getMessage(), e);
            return null;
        }
    }

    @Override
    protected void processPaymentOk(PaymentOkEvent eventOk) {
        log.debug("processPaymentOk() - Pay accepted");

        BigDecimal amount = eventOk.getAmount();
        String paymentCode = ((PaymentMethodManager) eventOk.getSource()).getPaymentCode();
        Integer paymentId = eventOk.getPaymentId();

        MedioPagoBean paymentMethod = mediosPagosService.getMedioPago(paymentCode);

        boolean cashFlowRecorded = ((PaymentMethodManager) eventOk.getSource()).recordCashFlowImmediately();

        PagoTicket payment = ticketManager.addPayToTicket(paymentCode, amount, paymentId, true, cashFlowRecorded);

        if (paymentMethod.getTarjetaCredito() != null && paymentMethod.getTarjetaCredito()) {
            if (eventOk.getExtendedData().containsKey(BasicPaymentMethodManager.PARAM_RESPONSE_TEF)) {
                DatosRespuestaPagoTarjeta datosRespuestaPagoTarjeta = (DatosRespuestaPagoTarjeta) eventOk.getExtendedData().get(BasicPaymentMethodManager.PARAM_RESPONSE_TEF);
                payment.setDatosRespuestaPagoTarjeta(datosRespuestaPagoTarjeta);
            }
        }

        payment.setExtendedData(eventOk.getExtendedData());

        TenderAccepted response = new TenderAccepted();
        response.setFieldIntValue(TenderAccepted.Amount, amount);
        response.setFieldValue(TenderAccepted.TenderType, comerzziaPaymentCodeToScoTenderType(paymentCode));
        response.setFieldValue(TenderAccepted.Description, paymentMethod.getDesMedioPago());

        ncrController.sendMessage(response);

        itemsManager.sendTotals();
    }

    private static class GiftCardProcessingException extends Exception {
        private static final long serialVersionUID = 1L;

        GiftCardProcessingException(String message) {
            super(message);
        }
    }
}
