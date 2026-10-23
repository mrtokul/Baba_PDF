package tr.baba.pdf

import java.io.IOException

/** Uzun işlemler için ilerleme bildirimi ve iptal bayrağı (arka plan iş parçacığından kullanılır). */
object Progress {
    @Volatile var cancelled = false
    @Volatile var sink: ((Int, Int) -> Unit)? = null

    fun reset() {
        cancelled = false
        sink?.invoke(0, 0)
    }

    /** [done]/[total] ilerlemeyi bildirir ve iptal istendiyse işlemi keser. */
    fun step(done: Int, total: Int) {
        if (cancelled) throw IOException("İşlem iptal edildi")
        sink?.invoke(done, total)
    }
}
