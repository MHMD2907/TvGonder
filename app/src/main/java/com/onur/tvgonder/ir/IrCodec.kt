// v0.28 – 2026-10-19: kızılötesi kod çözücü/kodlayıcı: Flipper .ir dosyası → telefonun IR vericisine gidecek darbe dizisi (NEC, Samsung, RC5/6, Sony, Panasonic, RCA, Pioneer, ham)
package com.onur.tvgonder.ir

/** Bir tuşun gönderilmeye hazır hali: taşıyıcı frekans + açık/kapalı süreleri (µs). */
class IrSignal(val name: String, val freq: Int, val pattern: IntArray)

object IrCodec {
    /** RC5/RC6 "yeniden basıldı" biti her basışta değişir. */
    @Volatile private var toggle = 0

    private class Block(val fields: MutableMap<String, String> = mutableMapOf())

    /** .ir dosyasını tuş listesine çevirir; desteklenmeyen tuşlar atlanır. */
    fun parse(text: String): List<IrSignal> {
        val blocks = mutableListOf<Block>()
        var cur: Block? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            if (k == "name") { cur = Block().also { blocks.add(it) } }
            cur?.fields?.put(k, v)
        }
        return blocks.mapNotNull { b -> runCatching { build(b) }.getOrNull() }
    }

    private fun build(b: Block): IrSignal? {
        val name = b.fields["name"] ?: return null
        return when (b.fields["type"]?.lowercase()) {
            "raw" -> {
                val f = b.fields["frequency"]?.toIntOrNull() ?: 38000
                val d = b.fields["data"]?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull()?.let { x -> kotlin.math.abs(x) } }?.filter { it > 0 } ?: return null
                if (d.isEmpty()) return null
                IrSignal(name, f, (if (d.size % 2 == 0 && d.size > 1) d.dropLast(1) else d).toIntArray())
            }
            "parsed" -> {
                val proto = b.fields["protocol"] ?: return null
                val a = hex(b.fields["address"] ?: "")
                val c = hex(b.fields["command"] ?: "")
                encode(proto, a, c)?.let { (f, p) -> IrSignal(name, f, p) }
            }
            else -> null
        }
    }

    /** "80 02 20 00" → küçük uçlu sayı (0x00200280). */
    private fun hex(s: String): Long {
        val bytes = s.split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }
        var v = 0L
        bytes.forEachIndexed { i, x -> v = v or ((x.toLong() and 0xFF) shl (8 * i)) }
        return v
    }

    // ---------------------------------------------------------------- darbe dizisi kurucu

    private class P {
        val l = ArrayList<Int>()
        private var mark = false
        fun mark(us: Int) = add(true, us)
        fun space(us: Int) = add(false, us)
        private fun add(on: Boolean, us: Int) {
            if (l.isEmpty()) { if (!on) return; l.add(us); mark = true; return }
            if (on == mark) l[l.lastIndex] = l.last() + us else { l.add(us); mark = on }
        }
        fun out(): IntArray = (if (!mark && l.isNotEmpty()) l.dropLast(1) else l).toIntArray()
    }

    /** Bitleri düşükten yükseğe yollar (NEC ailesi, Samsung, Sony, Panasonic). */
    private fun lsb(p: P, v: Long, n: Int, mark: Int, one: Int, zero: Int) {
        for (i in 0 until n) { p.mark(mark); p.space(if ((v shr i) and 1L == 1L) one else zero) }
    }

    private fun encode(proto: String, a: Long, c: Long): Pair<Int, IntArray>? {
        val p = P()
        when (proto.uppercase()) {
            "NEC", "NECEXT", "NEC42" -> {
                p.mark(9000); p.space(4500)
                when (proto.uppercase()) {
                    "NEC" -> {
                        val ad = a and 0xFF; val cm = c and 0xFF
                        lsb(p, ad, 8, 560, 1690, 560); lsb(p, ad.inv() and 0xFF, 8, 560, 1690, 560)
                        lsb(p, cm, 8, 560, 1690, 560); lsb(p, cm.inv() and 0xFF, 8, 560, 1690, 560)
                    }
                    "NECEXT" -> { lsb(p, a and 0xFFFF, 16, 560, 1690, 560); lsb(p, c and 0xFFFF, 16, 560, 1690, 560) }
                    else -> {
                        val ad = a and 0x1FFF; val cm = c and 0xFF
                        lsb(p, ad, 13, 560, 1690, 560); lsb(p, ad.inv() and 0x1FFF, 13, 560, 1690, 560)
                        lsb(p, cm, 8, 560, 1690, 560); lsb(p, cm.inv() and 0xFF, 8, 560, 1690, 560)
                    }
                }
                p.mark(560)
                return 38000 to p.out()
            }
            "SAMSUNG32" -> {
                val ad = a and 0xFF; val cm = c and 0xFF
                p.mark(4500); p.space(4500)
                lsb(p, ad, 8, 550, 1650, 550); lsb(p, ad, 8, 550, 1650, 550)
                lsb(p, cm, 8, 550, 1650, 550); lsb(p, cm.inv() and 0xFF, 8, 550, 1650, 550)
                p.mark(550)
                return 38000 to p.out()
            }
            "PIONEER" -> {
                val ad = a and 0xFF; val cm = c and 0xFF
                repeat(2) { r ->
                    if (r == 1) p.space(26000)
                    p.mark(8500); p.space(4250)
                    lsb(p, ad, 8, 500, 1500, 500); lsb(p, ad.inv() and 0xFF, 8, 500, 1500, 500)
                    lsb(p, cm, 8, 500, 1500, 500); lsb(p, cm.inv() and 0xFF, 8, 500, 1500, 500)
                    p.mark(500)
                }
                return 40000 to p.out()
            }
            "RC5", "RC5X" -> {
                val t = 889
                toggle = toggle xor 1
                val x = proto.uppercase() == "RC5X"
                val bits = ArrayList<Int>()
                bits.add(1)
                bits.add(if (x) (if ((c shr 6) and 1L == 1L) 0 else 1) else 1)
                bits.add(toggle)
                for (i in 4 downTo 0) bits.add(((a shr i) and 1L).toInt())
                for (i in 5 downTo 0) bits.add(((c shr i) and 1L).toInt())
                for (bt in bits) if (bt == 1) { p.space(t); p.mark(t) } else { p.mark(t); p.space(t) }
                return 36000 to p.out()
            }
            "RC6" -> {
                val t = 444
                toggle = toggle xor 1
                p.mark(6 * t); p.space(2 * t)
                p.mark(t); p.space(t)                                   // başlangıç biti (1)
                repeat(3) { p.space(t); p.mark(t) }                      // mod 000
                if (toggle == 1) { p.mark(2 * t); p.space(2 * t) } else { p.space(2 * t); p.mark(2 * t) }
                for (i in 7 downTo 0) if ((a shr i) and 1L == 1L) { p.mark(t); p.space(t) } else { p.space(t); p.mark(t) }
                for (i in 7 downTo 0) if ((c shr i) and 1L == 1L) { p.mark(t); p.space(t) } else { p.space(t); p.mark(t) }
                return 36000 to p.out()
            }
            "SIRC", "SIRC15", "SIRC20" -> {
                val n = when (proto.uppercase()) { "SIRC" -> 5; "SIRC15" -> 8; else -> 13 }
                repeat(3) { r ->
                    val f = P()
                    f.mark(2400); f.space(600)
                    for (i in 0 until 7) { f.mark(if ((c shr i) and 1L == 1L) 1200 else 600); f.space(600) }
                    for (i in 0 until n) { f.mark(if ((a shr i) and 1L == 1L) 1200 else 600); f.space(600) }
                    val frame = f.out()
                    if (r > 0) p.space(maxOf(10_000, 45_000 - frame.sum()))
                    var on = true
                    for (d in frame) { if (on) p.mark(d) else p.space(d); on = !on }
                }
                return 40000 to p.out()
            }
            "KASEIKYO" -> {
                val id = ((a shr 24) and 3).toInt()
                val vendor = ((a shr 8) and 0xFFFF).toInt()
                val g1 = ((a shr 4) and 0xF).toInt()
                val g2 = (a and 0xF).toInt()
                val cmd = (c and 0x3FF).toInt()
                var vp = vendor xor (vendor shr 8)
                vp = (vp xor (vp shr 4)) and 0xF
                val d = IntArray(6)
                d[0] = vendor and 0xFF; d[1] = (vendor shr 8) and 0xFF
                d[2] = vp or (g1 shl 4)
                d[3] = g2 or ((cmd and 0xF) shl 4)
                d[4] = (id shl 6) or (cmd shr 4)
                d[5] = d[2] xor d[3] xor d[4]
                p.mark(3456); p.space(1728)
                for (byte in d) lsb(p, byte.toLong(), 8, 432, 1296, 432)
                p.mark(432)
                return 37000 to p.out()
            }
            "RCA" -> {
                val ad = a and 0xF; val cm = c and 0xFF
                p.mark(4000); p.space(4000)
                fun msb(v: Long, n: Int) { for (i in n - 1 downTo 0) { p.mark(500); p.space(if ((v shr i) and 1L == 1L) 2000 else 1000) } }
                msb(ad, 4); msb(cm, 8); msb(ad.inv() and 0xF, 4); msb(cm.inv() and 0xFF, 8)
                p.mark(500)
                return 56000 to p.out()
            }
        }
        return null
    }
}
