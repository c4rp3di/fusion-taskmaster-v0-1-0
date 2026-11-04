package com.vigia.taskmaster.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Gráficos dibujados a mano (sin librerías): barra apilada, donut, líneas y barras por día. Son vistas normales, así que
 * se pueden poner en cualquier pantalla y heredan los colores del tema.
 */

/** Barra horizontal redondeada, con uno o varios segmentos que suman como máximo 1. */
class BarraView @JvmOverloads constructor(contexto: Context, atributos: AttributeSet? = null) : View(contexto, atributos) {

    class Segmento(val fraccion: Float, val color: Int)

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()
    private val ruta = Path()
    var altoDp = 10

    var segmentos: List<Segmento> = emptyList()
        set(valor) {
            field = valor
            invalidate()
        }

    fun simple(fraccion: Float, color: Int) {
        segmentos = listOf(Segmento(fraccion, color))
    }

    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        setMeasuredDimension(resolveSize(Ui.dp(context, 100), anchoSpec), Ui.dp(context, altoDp))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radio = h / 2f
        area.set(0f, 0f, w, h)
        pincel.style = Paint.Style.FILL
        pincel.color = Color.argb(45, 128, 128, 128)
        canvas.drawRoundRect(area, radio, radio, pincel)
        canvas.save()
        ruta.reset()
        ruta.addRoundRect(area, radio, radio, Path.Direction.CW)
        canvas.clipPath(ruta)
        var x = 0f
        for (s in segmentos) {
            val ancho = w * s.fraccion.coerceIn(0f, 1f)
            if (ancho <= 0f) continue
            pincel.color = s.color
            canvas.drawRect(x, 0f, x + ancho, h, pincel)
            x += ancho
        }
        canvas.restore()
    }
}

/** Anillo con porciones de colores y un texto en el centro. */
class DonutView @JvmOverloads constructor(contexto: Context, atributos: AttributeSet? = null) : View(contexto, atributos) {

    class Porcion(val valor: Float, val color: Int)

    private val pincelArco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pincelTexto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()

    var porciones: List<Porcion> = emptyList()
        set(valor) {
            field = valor
            invalidate()
        }

    var textoCentro: String = ""
        set(valor) {
            field = valor
            invalidate()
        }

    var subtexto: String = ""
        set(valor) {
            field = valor
            invalidate()
        }

    init {
        pincelArco.style = Paint.Style.STROKE
        pincelArco.strokeCap = Paint.Cap.BUTT
        pincelTexto.textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        val lado = minOf(resolveSize(Ui.dp(context, 190), anchoSpec), Ui.dp(context, 210))
        setMeasuredDimension(lado, lado)
    }

    override fun onDraw(canvas: Canvas) {
        val lado = minOf(width, height).toFloat()
        val grosor = lado * 0.13f
        pincelArco.strokeWidth = grosor
        val margen = grosor / 2f + 2f
        area.set(margen, margen, lado - margen, lado - margen)
        pincelArco.color = Color.argb(45, 128, 128, 128)
        canvas.drawArc(area, 0f, 360f, false, pincelArco)
        val total = porciones.sumOf { it.valor.toDouble() }.toFloat()
        if (total > 0f) {
            var inicio = -90f
            for (p in porciones) {
                val giro = p.valor / total * 360f
                if (giro <= 0f) continue
                pincelArco.color = p.color
                canvas.drawArc(area, inicio, maxOf(giro - 1.5f, 0.5f), false, pincelArco)
                inicio += giro
            }
        }
        val cx = width / 2f
        val cy = height / 2f
        pincelTexto.color = Tema.texto(context)
        pincelTexto.textSize = lado * 0.19f
        pincelTexto.isFakeBoldText = true
        canvas.drawText(textoCentro, cx, cy + lado * 0.02f, pincelTexto)
        if (subtexto.isNotEmpty()) {
            pincelTexto.color = Tema.gris(context)
            pincelTexto.textSize = lado * 0.08f
            pincelTexto.isFakeBoldText = false
            canvas.drawText(subtexto, cx, cy + lado * 0.13f, pincelTexto)
        }
    }
}

/** Gráfico de líneas con una o dos series; se escala solo (o con un máximo fijo). */
class LineaView @JvmOverloads constructor(contexto: Context, atributos: AttributeSet? = null) : View(contexto, atributos) {

    private val pincelLinea = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pincelRelleno = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pincelRejilla = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pincelTexto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ruta = Path()
    private val rutaRelleno = Path()

    var serie1: List<Float> = emptyList()
        set(valor) {
            field = valor
            invalidate()
        }

    var serie2: List<Float> = emptyList()
        set(valor) {
            field = valor
            invalidate()
        }

    var color1: Int = Color.CYAN
    var color2: Int = Color.MAGENTA
    var maximoFijo: Float? = null
    var unidad: String = ""
    var altoDp = 110

    init {
        pincelLinea.style = Paint.Style.STROKE
        pincelLinea.strokeJoin = Paint.Join.ROUND
        pincelLinea.strokeCap = Paint.Cap.ROUND
        pincelRelleno.style = Paint.Style.FILL
        pincelRejilla.style = Paint.Style.STROKE
        pincelRejilla.color = Color.argb(40, 128, 128, 128)
        pincelTexto.textSize = 28f
    }

    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        setMeasuredDimension(resolveSize(Ui.dp(context, 100), anchoSpec), Ui.dp(context, altoDp))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val arriba = Ui.dp(context, 14).toFloat()
        val util = h - arriba
        pincelRejilla.strokeWidth = 1f
        for (i in 0..2) {
            val y = arriba + util * i / 2f
            canvas.drawLine(0f, y, w, y, pincelRejilla)
        }
        var maximo = maximoFijo ?: maxOf(serie1.maxOrNull() ?: 0f, serie2.maxOrNull() ?: 0f)
        if (maximo <= 0f) maximo = 1f
        dibujar(canvas, serie1, color1, maximo, w, arriba, util, true)
        dibujar(canvas, serie2, color2, maximo, w, arriba, util, false)
        pincelTexto.color = Tema.gris(context)
        pincelTexto.textSize = Ui.dp(context, 10).toFloat()
        canvas.drawText(Formato.decimal(maximo.toDouble(), if (maximo < 10f) 1 else 0) + " " + unidad, 4f, arriba - 4f, pincelTexto)
    }

    private fun dibujar(canvas: Canvas, serie: List<Float>, color: Int, maximo: Float, w: Float, arriba: Float, util: Float, rellenar: Boolean) {
        if (serie.size < 2) return
        ruta.reset()
        rutaRelleno.reset()
        val paso = w / (serie.size - 1)
        for (i in serie.indices) {
            val x = i * paso
            val y = arriba + util - (serie[i].coerceIn(0f, maximo) / maximo) * util
            if (i == 0) {
                ruta.moveTo(x, y)
                rutaRelleno.moveTo(x, arriba + util)
                rutaRelleno.lineTo(x, y)
            } else {
                ruta.lineTo(x, y)
                rutaRelleno.lineTo(x, y)
            }
        }
        if (rellenar) {
            rutaRelleno.lineTo(w, arriba + util)
            rutaRelleno.close()
            pincelRelleno.color = Color.argb(50, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawPath(rutaRelleno, pincelRelleno)
        }
        pincelLinea.color = color
        pincelLinea.strokeWidth = Ui.dp(context, 2).toFloat()
        canvas.drawPath(ruta, pincelLinea)
    }
}

/** Barras verticales apiladas (dos valores por día), con la etiqueta de cada día debajo. */
class BarrasDiasView @JvmOverloads constructor(contexto: Context, atributos: AttributeSet? = null) : View(contexto, atributos) {

    class Dia(val etiqueta: String, val a: Float, val b: Float)

    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pincelTexto = Paint(Paint.ANTI_ALIAS_FLAG)
    private val area = RectF()

    var dias: List<Dia> = emptyList()
        set(valor) {
            field = valor
            invalidate()
        }

    var colorA: Int = Color.CYAN
    var colorB: Int = Color.MAGENTA
    var etiquetaMaximo: String = ""
    var altoDp = 170

    init {
        pincelTexto.textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(anchoSpec: Int, altoSpec: Int) {
        setMeasuredDimension(resolveSize(Ui.dp(context, 100), anchoSpec), Ui.dp(context, altoDp))
    }

    override fun onDraw(canvas: Canvas) {
        if (dias.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val abajo = Ui.dp(context, 18).toFloat()
        val arriba = Ui.dp(context, 14).toFloat()
        val util = h - abajo - arriba
        val maximo = maxOf(dias.maxOf { it.a + it.b }, 1f)
        val hueco = w / dias.size
        val ancho = hueco * 0.68f
        pincelTexto.textSize = Ui.dp(context, 10).toFloat()
        val cada = maxOf(1, Math.ceil(dias.size / 8.0).toInt())
        for (i in dias.indices) {
            val d = dias[i]
            val x0 = i * hueco + (hueco - ancho) / 2f
            val altoA = d.a / maximo * util
            val altoB = d.b / maximo * util
            val base = arriba + util
            pincel.color = colorA
            area.set(x0, base - altoA, x0 + ancho, base)
            canvas.drawRoundRect(area, 3f, 3f, pincel)
            pincel.color = colorB
            area.set(x0, base - altoA - altoB, x0 + ancho, base - altoA)
            canvas.drawRoundRect(area, 3f, 3f, pincel)
            if (i % cada == 0 || i == dias.size - 1) {
                pincelTexto.color = Tema.gris(context)
                canvas.drawText(d.etiqueta, x0 + ancho / 2f, h - 4f, pincelTexto)
            }
        }
        pincelTexto.textAlign = Paint.Align.LEFT
        pincelTexto.color = Tema.gris(context)
        canvas.drawText(etiquetaMaximo, 4f, arriba - 4f, pincelTexto)
        pincelTexto.textAlign = Paint.Align.CENTER
    }
}
