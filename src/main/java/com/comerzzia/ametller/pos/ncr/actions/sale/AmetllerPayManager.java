package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Lazy;
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
import com.comerzzia.pos.ncr.messages.BasicNCRMessage;
import com.comerzzia.pos.ncr.messages.DataNeeded;
import com.comerzzia.pos.ncr.messages.DataNeededReply;
import com.comerzzia.pos.ncr.messages.Tender;
import com.comerzzia.pos.ncr.messages.TenderException;
import com.comerzzia.pos.ncr.messages.TenderAccepted;
import com.comerzzia.pos.persistence.mediosPagos.MedioPagoBean;

@Lazy(false)
@Service
@Primary
@DependsOn("payManager")
public class AmetllerPayManager extends PayManager {

    private static final Logger log = Logger.getLogger(AmetllerPayManager.class);
    private static final String TENDER_TYPE_GIFT_CARD = "GIFTCARD";
    private static final Set<String> AUTO_DETECTED_TENDER_TYPES = new HashSet<>(
            Arrays.asList("OTRASTARJETAS", "OTHERCARDS"));
    private static final String DIALOG_CONFIRM_TYPE = "4";
    private static final String DIALOG_CONFIRM_ID = "1";
    private static final String[] PREFIJOS_TARJETAS_BEAN_NAMES = {
            "prefijosTarjetasService",
            "prefijosTarjetasSrv"
    };
    private static final String[] PREFIJOS_TARJETAS_CLASS_NAMES = {
            "com.comerzzia.pos.services.mediospagos.PrefijosTarjetasService",
            "com.comerzzia.pos.services.mediospagos.prefijos.PrefijosTarjetasService",
            "com.comerzzia.pos.services.payments.PrefijosTarjetasService",
            "com.comerzzia.pos.services.payments.methods.PrefijosTarjetasService",
            "com.comerzzia.pos.services.payments.methods.types.PrefijosTarjetasService",
            "com.comerzzia.ametller.pos.services.mediospagos.PrefijosTarjetasService"
    };

    @Autowired
    private Sesion sesion;

    @Autowired
    private VariablesServices variablesServices;

    @Autowired
    private ApplicationContext applicationContext;

    private PendingPaymentRequest pendingPayment;

    @PostConstruct
    @Override
    public void init() {
        super.init();
        ncrController.registerActionManager(DataNeededReply.class, this);
    }

    @Override
    public void processMessage(BasicNCRMessage message) {
        if (message instanceof DataNeededReply) {
            if (!handleDataNeededReply((DataNeededReply) message)) {
                log.warn("processMessage() - DataNeededReply not managed by gift card flow");
            }
            return;
        }

        super.processMessage(message);
    }

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

        if (!explicitGiftCard && !autoDetectedGiftCard) {
            super.trayPay(message);
            return;
        }

        if (ticketManager.isTrainingMode()) {
            super.trayPay(message);
            return;
        }

        PendingPaymentRequest paymentRequest = createPaymentRequest(message, tenderType, explicitGiftCard,
                autoDetectedGiftCard);

        if (paymentRequest == null) {
            return;
        }

        if (paymentRequest.autoDetected) {
            pendingPayment = paymentRequest;
            sendAutoDetectedPaymentDialog(paymentRequest);
            return;
        }

        executePayment(paymentRequest);
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

    private PendingPaymentRequest createPaymentRequest(Tender message, String scoTenderType, boolean explicitSelection,
            boolean autoDetectedSelection) {
        String numeroTarjeta = StringUtils.trimToNull(message.getFieldValue(Tender.UPC));

        if (StringUtils.isBlank(numeroTarjeta)) {
            log.warn("createPaymentRequest() - Número de tarjeta vacío");
            sendGiftCardError(I18N.getTexto("No se ha informado ningún número de tarjeta regalo."), scoTenderType);
            return null;
        }

        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

        if (paymentsManager == null) {
            log.error("createPaymentRequest() - Payments manager not available");
            sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."), scoTenderType);
            return null;
        }

        BigDecimal amount = parseAmount(message.getFieldValue(Tender.Amount));
        String paymentMethodCode;

        if (explicitSelection) {
            try {
                paymentMethodCode = scoTenderTypeToComerzziaPaymentCode(scoTenderType);
            }
            catch (RuntimeException e) {
                log.error("createPaymentRequest() - " + e.getMessage(), e);
                sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."), scoTenderType);
                return null;
            }
        }
        else {
            paymentMethodCode = getPaymentCodeFromPrefixes(numeroTarjeta);

            if (StringUtils.isBlank(paymentMethodCode)) {
                paymentMethodCode = findUniqueGiftCardPaymentCode(paymentsManager);
            }
        }

        if (StringUtils.isBlank(paymentMethodCode)) {
            log.error("createPaymentRequest() - Medio de pago de tarjeta regalo no configurado");
            sendGiftCardError(I18N.getTexto("Medio de pago no encontrado."), scoTenderType);
            return null;
        }

        PaymentMethodManager paymentMethodManager = findPaymentMethodManager(paymentsManager, paymentMethodCode);

        if (!(paymentMethodManager instanceof GiftCardManager)) {
            log.error("createPaymentRequest() - Payment manager " + paymentMethodCode + " is not a gift card manager");
            sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."), scoTenderType);
            return null;
        }

        MedioPagoBean medioPago = null;

        try {
            if (mediosPagosService != null) {
                medioPago = mediosPagosService.getMedioPago(paymentMethodCode);
            }
        }
        catch (Exception e) {
            log.debug("createPaymentRequest() - No se ha podido recuperar la descripción del medio de pago "
                    + paymentMethodCode, e);
        }

        return new PendingPaymentRequest(message, paymentMethodCode, paymentMethodManager, medioPago, numeroTarjeta,
                amount, scoTenderType, autoDetectedSelection);
    }

    private void sendAutoDetectedPaymentDialog(PendingPaymentRequest paymentRequest) {
        DataNeeded dialog = new DataNeeded();
        dialog.setFieldValue(DataNeeded.Type, DIALOG_CONFIRM_TYPE);
        dialog.setFieldValue(DataNeeded.Id, DIALOG_CONFIRM_ID);
        dialog.setFieldValue(DataNeeded.Mode, "0");

        String description = paymentRequest.medioPago != null ? paymentRequest.medioPago.getDesMedioPago()
                : paymentRequest.paymentMethodCode;
        String caption = MessageFormat.format("¿Desea usar su tarjeta {0}?", description);

        dialog.setFieldValue(DataNeeded.TopCaption1, caption);
        dialog.setFieldValue(DataNeeded.SummaryInstruction1, "Pulse una opción");
        dialog.setFieldValue(DataNeeded.ButtonData1, "TxSi");
        dialog.setFieldValue(DataNeeded.ButtonText1, "SI");
        dialog.setFieldValue(DataNeeded.ButtonData2, "TxNo");
        dialog.setFieldValue(DataNeeded.ButtonText2, "NO");
        dialog.setFieldValue(DataNeeded.EnableScanner, "0");
        dialog.setFieldValue(DataNeeded.HideGoBack, "1");

        ncrController.sendMessage(dialog);
    }

    private boolean handleDataNeededReply(DataNeededReply message) {
        if (message == null) {
            return false;
        }

        String type = message.getFieldValue(DataNeededReply.Type);
        String id = message.getFieldValue(DataNeededReply.Id);

        if (!StringUtils.equals(DIALOG_CONFIRM_TYPE, type) || !StringUtils.equals(DIALOG_CONFIRM_ID, id)) {
            return false;
        }

        PendingPaymentRequest paymentRequest = pendingPayment;
        pendingPayment = null;

        sendCloseDataNeeded();

        if (paymentRequest == null) {
            log.warn("handleDataNeededReply() - Confirmation received without pending payment");
            return true;
        }

        String option = StringUtils.trimToEmpty(message.getFieldValue(DataNeededReply.Data1));

        if (StringUtils.equalsIgnoreCase("TxSi", option)) {
            executePayment(paymentRequest);
        }
        else {
            sendGiftCardError(I18N.getTexto("Operación cancelada por el usuario"), paymentRequest.scoTenderType);
        }

        return true;
    }

    private void sendCloseDataNeeded() {
        DataNeeded closeMessage = new DataNeeded();
        closeMessage.setFieldValue(DataNeeded.Type, "0");
        closeMessage.setFieldValue(DataNeeded.Id, "0");
        closeMessage.setFieldValue(DataNeeded.Mode, "0");

        ncrController.sendMessage(closeMessage);
    }

    private void executePayment(PendingPaymentRequest paymentRequest) {
        if (paymentRequest == null) {
            return;
        }

        pendingPayment = null;

        PaymentsManager paymentsManager = ticketManager.getPaymentsManager();

        if (paymentsManager == null) {
            log.error("executePayment() - Payments manager not available");
            sendGiftCardError(I18N.getTexto("Medio de pago de tarjeta regalo no disponible."),
                    paymentRequest.scoTenderType);
            return;
        }

        boolean waitStateSent = false;

        try {
            ncrController.sendWaitState(I18N.getTexto("Validando tarjeta..."));
            waitStateSent = true;

            GiftCardBean giftCard = loadGiftCard(paymentRequest.numeroTarjeta);
            normalizeGiftCard(giftCard, paymentRequest.numeroTarjeta);

            BigDecimal amountToCharge = resolveGiftCardAmount(paymentRequest, giftCard);
            paymentRequest.amount = amountToCharge;

            updateGiftCardAmount(giftCard, amountToCharge);

            paymentRequest.paymentMethodManager.addParameter(GiftCardManager.PARAM_TARJETA, giftCard);

            paymentRequest.tenderMessage.setFieldIntValue(Tender.Amount, amountToCharge);

            paymentsManager.pay(paymentRequest.paymentMethodCode, amountToCharge);
        }
        catch (GiftCardProcessingException e) {
            log.error("executePayment() - " + e.getMessage(), e);
            handleGiftCardError(paymentsManager, paymentRequest.paymentMethodManager, e, e.getMessage(),
                    paymentRequest.scoTenderType);
        }
        catch (Exception e) {
            log.error("executePayment() - Error inesperado: " + e.getMessage(), e);
            handleGiftCardError(paymentsManager, paymentRequest.paymentMethodManager, e, e.getMessage(),
                    paymentRequest.scoTenderType);
        }
        finally {
            if (waitStateSent) {
                ncrController.sendFinishWaitState();
            }
        }
    }

    private PaymentMethodManager findPaymentMethodManager(PaymentsManager paymentsManager, String paymentMethodCode) {
        Map<String, PaymentMethodManager> managers = getAvailablePaymentManagers(paymentsManager);

        if (managers.containsKey(paymentMethodCode)) {
            return managers.get(paymentMethodCode);
        }

        try {
            Method method = paymentsManager.getClass().getMethod("getPaymentMethodManager", String.class);
            Object result = method.invoke(paymentsManager, paymentMethodCode);

            if (result instanceof PaymentMethodManager) {
                return (PaymentMethodManager) result;
            }
        }
        catch (NoSuchMethodException e) {
            log.debug("findPaymentMethodManager() - Método getPaymentMethodManager no disponible");
        }
        catch (Exception e) {
            log.error("findPaymentMethodManager() - Error obteniendo el medio de pago " + paymentMethodCode + ": "
                    + e.getMessage(), e);
        }

        return managers.get(paymentMethodCode);
    }

    @SuppressWarnings("unchecked")
    private Map<String, PaymentMethodManager> getAvailablePaymentManagers(PaymentsManager paymentsManager) {
        if (paymentsManager == null) {
            return Collections.emptyMap();
        }

        List<String> methodNames = Arrays.asList("getPaymentsMehtodManagerAvailables",
                "getPaymentsMethodManagerAvailables", "getPaymentMethodManagers");

        for (String methodName : methodNames) {
            try {
                Method method = paymentsManager.getClass().getMethod(methodName);
                Object result = method.invoke(paymentsManager);

                if (result instanceof Map) {
                    return (Map<String, PaymentMethodManager>) result;
                }
            }
            catch (NoSuchMethodException e) {
                log.debug("getAvailablePaymentManagers() - Método " + methodName + " no disponible");
            }
            catch (Exception e) {
                log.error("getAvailablePaymentManagers() - Error invocando " + methodName + ": " + e.getMessage(), e);
            }
        }

        return Collections.emptyMap();
    }

    private String findUniqueGiftCardPaymentCode(PaymentsManager paymentsManager) {
        Map<String, PaymentMethodManager> managers = getAvailablePaymentManagers(paymentsManager);
        String candidate = null;

        for (Map.Entry<String, PaymentMethodManager> entry : managers.entrySet()) {
            if (entry.getValue() instanceof GiftCardManager) {
                if (candidate != null) {
                    return null;
                }
                candidate = entry.getKey();
            }
        }

        return candidate;
    }

    private String getPaymentCodeFromPrefixes(String numeroTarjeta) {
        if (StringUtils.isBlank(numeroTarjeta)) {
            return null;
        }

        Object service = findPrefijosTarjetasService();

        if (service == null) {
            log.warn("getPaymentCodeFromPrefixes() - PrefijosTarjetasService not available");
            return null;
        }

        try {
            Method method = service.getClass().getMethod("getMedioPagoPrefijo", String.class);
            Object result = method.invoke(service, numeroTarjeta);
            return result != null ? result.toString() : null;
        }
        catch (NoSuchMethodException e) {
            log.warn("getPaymentCodeFromPrefixes() - Método getMedioPagoPrefijo no disponible en "
                    + service.getClass().getName());
        }
        catch (Exception e) {
            log.error("getPaymentCodeFromPrefixes() - Error al consultar el prefijo de la tarjeta: " + e.getMessage(), e);
        }

        return null;
    }

    private Object findPrefijosTarjetasService() {
        if (applicationContext == null) {
            return null;
        }

        for (String beanName : PREFIJOS_TARJETAS_BEAN_NAMES) {
            if (applicationContext.containsBean(beanName)) {
                try {
                    return applicationContext.getBean(beanName);
                }
                catch (Exception e) {
                    log.debug("findPrefijosTarjetasService() - No se ha podido obtener el bean " + beanName, e);
                }
            }
        }

        for (String className : PREFIJOS_TARJETAS_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);
                return applicationContext.getBean(clazz);
            }
            catch (ClassNotFoundException e) {
                log.debug("findPrefijosTarjetasService() - Clase no disponible: " + className);
            }
            catch (Exception e) {
                log.debug("findPrefijosTarjetasService() - Error obteniendo bean para " + className + ": "
                        + e.getMessage(), e);
            }
        }

        return null;
    }

    private GiftCardBean loadGiftCard(String numeroTarjeta) throws GiftCardProcessingException {
        List<Method> methods = findGiftCardConsultMethods();
        GiftCardProcessingException lastError = null;

        for (Method method : methods) {
            try {
                Object response = invokeGiftCardMethod(method, numeroTarjeta);
                GiftCardBean bean = adaptGiftCardResponse(response, numeroTarjeta);

                if (bean != null) {
                    return bean;
                }
            }
            catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                String message = cause != null ? cause.getMessage() : e.getMessage();
                log.debug("loadGiftCard() - Error invoking " + method.getName() + ": " + message, cause);
                lastError = new GiftCardProcessingException(
                        I18N.getTexto("Ha habido un error al validar la tarjeta regalo: {0}", message));
            }
            catch (IllegalArgumentException e) {
                log.debug("loadGiftCard() - Arguments not compatible for method " + method.getName());
            }
            catch (IllegalAccessException e) {
                log.debug("loadGiftCard() - Cannot access method " + method.getName() + ": " + e.getMessage(), e);
                lastError = new GiftCardProcessingException(
                        I18N.getTexto("No se ha podido acceder al servicio de tarjetas regalo."));
            }
        }

        Object fidelizadosResponse = fetchGiftCardFromFidelizados(numeroTarjeta);
        GiftCardBean bean = adaptGiftCardResponse(fidelizadosResponse, numeroTarjeta);

        if (bean != null) {
            return bean;
        }

        if (lastError != null) {
            throw lastError;
        }

        throw new GiftCardProcessingException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
    }

    private Object invokeGiftCardMethod(Method method, String numeroTarjeta) throws IllegalAccessException, InvocationTargetException, GiftCardProcessingException {
        List<Object[]> candidates = buildGiftCardArguments(numeroTarjeta);
        InvocationTargetException lastInvocationException = null;
        IllegalArgumentException lastIllegalArgument = null;

        for (Object[] candidate : candidates) {
            if (candidate == null || candidate.length != method.getParameterCount()) {
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

    private Object fetchGiftCardFromFidelizados(String numeroTarjeta) {
        try {
            Class<?> restClass = Class.forName("com.comerzzia.api.rest.client.fidelizados.FidelizadosRest");

            for (Method method : restClass.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                String name = method.getName().toLowerCase(Locale.ROOT);

                if (!name.contains("tarjetaregalo")) {
                    continue;
                }

                try {
                    return invokeGiftCardMethod(method, numeroTarjeta);
                }
                catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    String message = cause != null ? cause.getMessage() : e.getMessage();
                    log.debug("fetchGiftCardFromFidelizados() - Error invoking " + method.getName() + ": " + message, cause);
                }
                catch (IllegalArgumentException | IllegalAccessException | GiftCardProcessingException e) {
                    log.debug("fetchGiftCardFromFidelizados() - Unable to invoke " + method.getName() + ": " + e.getMessage(), e);
                }
            }
        }
        catch (ClassNotFoundException e) {
            log.debug("fetchGiftCardFromFidelizados() - FidelizadosRest class not available");
        }

        return null;
    }

    private GiftCardBean adaptGiftCardResponse(Object response, String numeroTarjeta) {
        if (response == null) {
            return null;
        }

        if (response instanceof GiftCardBean) {
            GiftCardBean bean = (GiftCardBean) response;

            if (StringUtils.isBlank(bean.getNumTarjetaRegalo())) {
                bean.setNumTarjetaRegalo(numeroTarjeta);
            }

            return bean;
        }

        if (response instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<?, ?> map = (Map<?, ?>) response;

            Object nested = findMapValue(map, "tarjetaregalo", "giftcard", "tarjeta", "data");
            GiftCardBean nestedBean = adaptGiftCardResponse(nested, numeroTarjeta);

            if (nestedBean != null) {
                return nestedBean;
            }

            BigDecimal saldo = extractBigDecimalFromMap(map, "saldo", "balance", "saldoactual");
            BigDecimal saldoProvisional = extractBigDecimalFromMap(map, "saldoProvisional", "provisionalBalance");
            BigDecimal saldoTotal = extractBigDecimalFromMap(map, "saldoTotal", "totalBalance", "saldodisponible");

            if (saldo == null && saldoProvisional == null && saldoTotal == null) {
                return null;
            }

            GiftCardBean bean = new GiftCardBean();
            bean.setNumTarjetaRegalo(numeroTarjeta);
            applyBalances(bean, saldo, saldoProvisional, saldoTotal);
            return bean;
        }

        Method nestedMethod = findMethod(response.getClass(), "getTarjetaRegalo", "getGiftCard", "getTarjeta", "getData");

        if (nestedMethod != null) {
            try {
                Object nested = nestedMethod.invoke(response);
                GiftCardBean bean = adaptGiftCardResponse(nested, numeroTarjeta);

                if (bean != null) {
                    return bean;
                }
            }
            catch (Exception e) {
                log.debug("adaptGiftCardResponse() - Error invoking nested getter on "
                        + response.getClass().getName() + ": " + e.getMessage(), e);
            }
        }

        BigDecimal saldo = extractBigDecimal(response, "getSaldo", "getBalance", "getSaldoActual");
        BigDecimal saldoProvisional = extractBigDecimal(response, "getSaldoProvisional", "getProvisionalBalance");
        BigDecimal saldoTotal = extractBigDecimal(response, "getSaldoTotal", "getSaldoDisponible", "getBalanceTotal");

        if (saldo == null && saldoProvisional == null && saldoTotal == null) {
            return null;
        }

        GiftCardBean bean = new GiftCardBean();
        bean.setNumTarjetaRegalo(numeroTarjeta);
        applyBalances(bean, saldo, saldoProvisional, saldoTotal);
        return bean;
    }

    private void applyBalances(GiftCardBean bean, BigDecimal saldo, BigDecimal saldoProvisional, BigDecimal saldoTotal) {
        BigDecimal saldoValue = saldo != null ? saldo : BigDecimal.ZERO;
        BigDecimal provisionalValue = saldoProvisional != null ? saldoProvisional : BigDecimal.ZERO;

        bean.setSaldo(saldoValue);
        bean.setSaldoProvisional(provisionalValue);

        if (saldoTotal != null) {
            BigDecimal total = saldoValue.add(provisionalValue);

            if (total.compareTo(saldoTotal) != 0) {
                bean.setSaldo(saldoTotal);
                bean.setSaldoProvisional(BigDecimal.ZERO);
            }
        }
    }

    private BigDecimal extractBigDecimal(Object source, String... getterNames) {
        if (source == null || getterNames == null) {
            return null;
        }

        for (String getterName : getterNames) {
            Method method = findMethod(source.getClass(), getterName);

            if (method == null) {
                continue;
            }

            try {
                Object value = method.invoke(source);
                BigDecimal decimal = toBigDecimal(value);

                if (decimal != null) {
                    return decimal;
                }
            }
            catch (Exception e) {
                log.debug("extractBigDecimal() - Unable to invoke " + getterName + " on "
                        + source.getClass().getName() + ": " + e.getMessage(), e);
            }
        }

        return null;
    }

    private BigDecimal extractBigDecimalFromMap(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            Object value = findMapValue(map, key);
            BigDecimal decimal = toBigDecimal(value);

            if (decimal != null) {
                return decimal;
            }
        }

        return null;
    }

    private Object findMapValue(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object entryKey = entry.getKey();

                if (entryKey != null && key.equalsIgnoreCase(entryKey.toString())) {
                    return entry.getValue();
                }
            }
        }

        return null;
    }

    private Method findMethod(Class<?> type, String... names) {
        if (type == null || names == null) {
            return null;
        }

        for (String name : names) {
            if (StringUtils.isBlank(name)) {
                continue;
            }

            Class<?> current = type;

            while (current != null) {
                for (Method method : current.getDeclaredMethods()) {
                    if (method.getParameterCount() == 0 && method.getName().equalsIgnoreCase(name)) {
                        method.setAccessible(true);
                        return method;
                    }
                }
                current = current.getSuperclass();
            }
        }

        return null;
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }

        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }

        if (value instanceof String) {
            String text = ((String) value).trim();

            if (text.isEmpty()) {
                return null;
            }

            try {
                return new BigDecimal(text);
            }
            catch (NumberFormatException e) {
                log.debug("toBigDecimal() - Value is not numeric: " + text);
                return null;
            }
        }

        return null;
    }

    private List<Method> findGiftCardConsultMethods() {
        List<Method> methods = new ArrayList<>();

        for (Method method : MovimientosRest.class.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            String name = method.getName().toLowerCase(Locale.ROOT);

            if (name.contains("tarjetaregalo")
                    && (name.contains("consult") || name.contains("obten") || name.contains("saldo")
                            || name.contains("buscar"))) {
                methods.add(method);
            }
        }

        return methods;
    }

    private BigDecimal resolveGiftCardAmount(PendingPaymentRequest paymentRequest, GiftCardBean giftCard)
            throws GiftCardProcessingException {
        if (giftCard == null) {
            throw new GiftCardProcessingException(I18N.getTexto("No se ha podido validar la tarjeta regalo."));
        }

        BigDecimal pending = ticketManager.getTicket().getTotales().getPendiente();
        BigDecimal saldo = giftCard.getSaldo();
        BigDecimal saldoProvisional = giftCard.getSaldoProvisional();

        if (saldo == null) {
            saldo = BigDecimal.ZERO;
        }

        if (saldoProvisional != null) {
            saldo = saldo.add(saldoProvisional);
        }

        BigDecimal requested = paymentRequest != null ? paymentRequest.amount : null;

        if (requested == null || requested.compareTo(BigDecimal.ZERO) <= 0) {
            requested = pending;
        }

        BigDecimal amount = requested != null ? requested : saldo;

        if (pending != null && amount != null && amount.compareTo(pending) > 0) {
            amount = pending;
        }

        if (amount == null || amount.compareTo(saldo) > 0) {
            amount = saldo;
        }

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
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
        handleGiftCardError(paymentsManager, paymentMethodManager, exception, errorMessage, null);
    }

    private void handleGiftCardError(PaymentsManager paymentsManager, PaymentMethodManager paymentMethodManager,
            Exception exception, String errorMessage, String tenderType) {
        String message = StringUtils.defaultIfBlank(errorMessage,
                I18N.getTexto("Ha habido un error al procesar el pago con tarjeta regalo."));

        sendGiftCardError(message, tenderType);

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
        sendGiftCardError(message, null);
    }

    private void sendGiftCardError(String message, String tenderType) {
        TenderException tenderException = new TenderException();
        tenderException.setFieldValue(TenderException.ExceptionId, "0");
        tenderException.setFieldValue(TenderException.ExceptionType, "0");
        String tenderValue = StringUtils.isNotBlank(tenderType) ? tenderType : "Gift Card";
        tenderException.setFieldValue(TenderException.TenderType, tenderValue);

        if (StringUtils.isNotBlank(message)) {
            tenderException.setFieldValue(TenderException.Message, message);
        }

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

    private static class PendingPaymentRequest {
        private final Tender tenderMessage;
        private final String paymentMethodCode;
        private final PaymentMethodManager paymentMethodManager;
        private final MedioPagoBean medioPago;
        private final String numeroTarjeta;
        private BigDecimal amount;
        private final String scoTenderType;
        private final boolean autoDetected;

        private PendingPaymentRequest(Tender tenderMessage, String paymentMethodCode,
                PaymentMethodManager paymentMethodManager, MedioPagoBean medioPago, String numeroTarjeta,
                BigDecimal amount, String scoTenderType, boolean autoDetected) {
            this.tenderMessage = tenderMessage;
            this.paymentMethodCode = paymentMethodCode;
            this.paymentMethodManager = paymentMethodManager;
            this.medioPago = medioPago;
            this.numeroTarjeta = numeroTarjeta;
            this.amount = amount;
            this.scoTenderType = scoTenderType;
            this.autoDetected = autoDetected;
        }
    }

    private static class GiftCardProcessingException extends Exception {
        private static final long serialVersionUID = 1L;

        GiftCardProcessingException(String message) {
            super(message);
        }
    }
}
