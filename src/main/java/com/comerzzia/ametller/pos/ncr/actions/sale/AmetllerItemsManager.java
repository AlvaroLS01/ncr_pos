package com.comerzzia.ametller.pos.ncr.actions.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.ametller.pos.ncr.ticket.AmetllerScoTicketManager;
import com.comerzzia.pos.ncr.actions.sale.ItemsManager;
import com.comerzzia.pos.ncr.messages.ItemSold;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;

@Lazy(false)
@Service
@Primary
public class AmetllerItemsManager extends ItemsManager {

    private static final String DESCRIPCION_DESCUENTO_25 = "Descuento del 25% aplicado";
    private static final BigDecimal FACTOR_PRECIO_DESCUENTO = new BigDecimal("0.75");
    private static final BigDecimal PORCENTAJE_DESCUENTO = new BigDecimal("0.25");

    @Override
    protected ItemSold lineaTicketToItemSold(LineaTicket linea) {
        ItemSold itemSold = super.lineaTicketToItemSold(linea);

        if (!(ticketManager instanceof AmetllerScoTicketManager)) {
            return itemSold;
        }

        AmetllerScoTicketManager ametllerScoTicketManager = (AmetllerScoTicketManager) ticketManager;

        if (!ametllerScoTicketManager.isLineaConDescuento25(linea)) {
            return itemSold;
        }

        BigDecimal cantidad = linea.getCantidad();
        if (cantidad == null || cantidad.compareTo(BigDecimal.ZERO) <= 0) {
            cantidad = BigDecimal.ONE;
        }

        BigDecimal importeSinDto = escalaDosDecimales(linea.getImporteTotalSinDto());
        BigDecimal importeConDto = escalaDosDecimales(linea.getImporteTotalConDto());

        if (importeConDto == null && importeSinDto != null) {
            importeConDto = importeSinDto.multiply(FACTOR_PRECIO_DESCUENTO).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal precioConDto = escalaDosDecimales(linea.getPrecioTotalConDto());

        if (precioConDto == null) {
            if (importeConDto != null && cantidad.compareTo(BigDecimal.ZERO) > 0) {
                precioConDto = importeConDto.divide(cantidad, 2, RoundingMode.HALF_UP);
            } else if (importeSinDto != null && cantidad.compareTo(BigDecimal.ZERO) > 0) {
                precioConDto = importeSinDto.divide(cantidad, 2, RoundingMode.HALF_UP).multiply(FACTOR_PRECIO_DESCUENTO)
                        .setScale(2, RoundingMode.HALF_UP);
            }
        }

        if (precioConDto != null) {
            itemSold.setFieldIntValue(ItemSold.Price, precioConDto);
        }

        if (importeConDto != null) {
            itemSold.setFieldIntValue(ItemSold.ExtendedPrice, importeConDto);
        }

        ItemSold discount = itemSold.getDiscountApplied();
        if (discount == null) {
            return itemSold;
        }

        BigDecimal descuentoCalculado = null;

        if (importeSinDto != null && importeConDto != null) {
            descuentoCalculado = importeSinDto.subtract(importeConDto);
        }

        if ((descuentoCalculado == null || descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0)
                && precioConDto != null) {
            BigDecimal precioSinDto = escalaDosDecimales(linea.getPrecioTotalSinDto());
            if (precioSinDto != null) {
                descuentoCalculado = precioSinDto.subtract(precioConDto).multiply(cantidad).setScale(2,
                        RoundingMode.HALF_UP);
            }
        }

        if ((descuentoCalculado == null || descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0)
                && linea.getImporteTotalPromociones() != null) {
            descuentoCalculado = escalaDosDecimales(linea.getImporteTotalPromociones());
        }

        if ((descuentoCalculado == null || descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0) && importeSinDto != null) {
            descuentoCalculado = importeSinDto.multiply(PORCENTAJE_DESCUENTO).setScale(2, RoundingMode.HALF_UP);
        }

        if (descuentoCalculado == null || descuentoCalculado.compareTo(BigDecimal.ZERO) <= 0) {
            return itemSold;
        }

        if (importeConDto == null && importeSinDto != null) {
            importeConDto = importeSinDto.subtract(descuentoCalculado).setScale(2, RoundingMode.HALF_UP);
        }

        if (importeConDto != null) {
            discount.setFieldIntValue(ItemSold.Price, importeConDto);
        }

        discount.setFieldValue(ItemSold.ItemNumber,
                String.valueOf(linea.getIdLinea() + PROMOTIONS_FIRST_ITEM_ID));
        discount.setFieldIntValue(ItemSold.DiscountAmount, descuentoCalculado.setScale(2, RoundingMode.HALF_UP));
        discount.setFieldValue(ItemSold.AssociatedItemNumber, String.valueOf(linea.getIdLinea()));
        discount.setFieldValue(ItemSold.RewardLocation, "3");
        discount.setFieldValue(ItemSold.ShowRewardPoints, "1");
        discount.setFieldValue(ItemSold.DiscountDescription, DESCRIPCION_DESCUENTO_25);
        discount.setFieldValue(ItemSold.Description, DESCRIPCION_DESCUENTO_25);

        return itemSold;
    }

    private BigDecimal escalaDosDecimales(BigDecimal valor) {
        if (valor == null) {
            return null;
        }
        return valor.setScale(2, RoundingMode.HALF_UP);
    }
}
