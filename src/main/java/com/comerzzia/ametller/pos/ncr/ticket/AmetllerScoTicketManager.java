package com.comerzzia.ametller.pos.ncr.ticket;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.comerzzia.pos.ncr.ticket.ScoTicketManager;
import com.comerzzia.pos.services.ticket.lineas.LineaTicket;
import com.comerzzia.pos.services.ticket.lineas.LineaTicketAbstract;

@Service
@Primary
public class AmetllerScoTicketManager extends ScoTicketManager {

    private static final BigDecimal DESCUENTO25 = new BigDecimal("25.00");
    private static final BigDecimal PORCENTAJE_DESCUENTO = new BigDecimal("0.25");
    private static final BigDecimal FACTOR_PRECIO_DESCUENTO = BigDecimal.ONE.subtract(PORCENTAJE_DESCUENTO);

    private final Map<Integer, Descuento25Info> descuentosPorLinea = new HashMap<>();
    private boolean descuento25Activo = false;

    public void setDescuento25Activo(boolean activo) {
        this.descuento25Activo = activo;
    }

    public boolean isLineaConDescuento25(LineaTicket linea) {
        return linea != null && descuentosPorLinea.containsKey(linea.getIdLinea());
    }

    public Descuento25Info getDescuento25Info(Integer idLinea) {
        if (idLinea == null) {
            return null;
        }
        return descuentosPorLinea.get(idLinea);
    }

    public BigDecimal getTotalDescuento25Aplicado() {
        BigDecimal total = BigDecimal.ZERO;

        Collection<Descuento25Info> valores = descuentosPorLinea.values();
        for (Descuento25Info info : valores) {
            if (info != null && info.getImporteDescuento() != null) {
                total = total.add(info.getImporteDescuento());
            }
        }

        return total.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public void initSession() {
        super.initSession();
        descuentosPorLinea.clear();
        descuento25Activo = false;
    }

    @Override
    public void ticketInitilize() {
        super.ticketInitilize();
        descuentosPorLinea.clear();
    }

    @Override
    public LineaTicket addLineToTicket(LineaTicketAbstract linea) {
        LineaTicket added = super.addLineToTicket(linea);
        if (added != null) {
            if (descuento25Activo) {
                aplicarDescuento25(added);
                recalculateTicket();
            } else {
                descuentosPorLinea.remove(added.getIdLinea());
            }
        }
        return added;
    }

    @Override
    public void deleteTicketLine(Integer idLinea) {
        super.deleteTicketLine(idLinea);
        descuentosPorLinea.remove(idLinea);
    }

    private void aplicarDescuento25(LineaTicket linea) {
        if (linea == null) {
            return;
        }

        BigDecimal cantidad = normalizaCantidad(linea.getCantidad());
        BigDecimal importeSinDto = escala(linea.getImporteTotalSinDto());
        BigDecimal precioSinDto = escala(linea.getPrecioTotalSinDto());

        if (importeSinDto == null && precioSinDto != null) {
            importeSinDto = precioSinDto.multiply(cantidad).setScale(2, RoundingMode.HALF_UP);
        }

        if ((precioSinDto == null || precioSinDto.compareTo(BigDecimal.ZERO) <= 0) && importeSinDto != null
                && cantidad.compareTo(BigDecimal.ZERO) > 0) {
            precioSinDto = importeSinDto.divide(cantidad, 2, RoundingMode.HALF_UP);
        }

        linea.setDescuentoManual(DESCUENTO25);
        linea.recalcularImporteFinal();

        BigDecimal importeConDto = escala(linea.getImporteTotalConDto());
        if (importeConDto == null && importeSinDto != null) {
            importeConDto = importeSinDto.multiply(FACTOR_PRECIO_DESCUENTO).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal precioConDto = escala(linea.getPrecioTotalConDto());
        if ((precioConDto == null || precioConDto.compareTo(BigDecimal.ZERO) <= 0) && importeConDto != null
                && cantidad.compareTo(BigDecimal.ZERO) > 0) {
            precioConDto = importeConDto.divide(cantidad, 2, RoundingMode.HALF_UP);
        }

        BigDecimal descuentoAplicado = BigDecimal.ZERO;
        if (importeSinDto != null && importeConDto != null) {
            descuentoAplicado = importeSinDto.subtract(importeConDto).setScale(2, RoundingMode.HALF_UP);
        } else if (importeSinDto != null) {
            descuentoAplicado = importeSinDto.multiply(PORCENTAJE_DESCUENTO).setScale(2, RoundingMode.HALF_UP);
        } else if (precioSinDto != null && precioConDto != null) {
            descuentoAplicado = precioSinDto.subtract(precioConDto).multiply(cantidad).setScale(2, RoundingMode.HALF_UP);
        }

        descuentosPorLinea.put(linea.getIdLinea(), new Descuento25Info(precioConDto, importeConDto, descuentoAplicado));
    }

    private BigDecimal normalizaCantidad(BigDecimal cantidad) {
        if (cantidad == null || cantidad.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ONE;
        }
        return cantidad;
    }

    private BigDecimal escala(BigDecimal valor) {
        if (valor == null) {
            return null;
        }
        return valor.setScale(2, RoundingMode.HALF_UP);
    }

    public static class Descuento25Info {
        private final BigDecimal precioConDto;
        private final BigDecimal importeConDto;
        private final BigDecimal importeDescuento;

        public Descuento25Info(BigDecimal precioConDto, BigDecimal importeConDto, BigDecimal importeDescuento) {
            this.precioConDto = precioConDto;
            this.importeConDto = importeConDto;
            this.importeDescuento = importeDescuento != null ? importeDescuento.setScale(2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        public BigDecimal getPrecioConDto() {
            return precioConDto;
        }

        public BigDecimal getImporteConDto() {
            return importeConDto;
        }

        public BigDecimal getImporteDescuento() {
            return importeDescuento;
        }
    }
}
