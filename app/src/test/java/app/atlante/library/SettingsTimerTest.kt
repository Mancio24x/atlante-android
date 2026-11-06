package app.atlante.library
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.atlante.library.model.Book
import app.atlante.library.model.ReadingJournal
import app.atlante.library.storage.LibraryStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class SettingsTimerTest {
    private fun call(a:MainActivity,name:String,vararg args:Any):Any? {
        val method=MainActivity::class.java.declaredMethods.first { it.name==name && it.parameterCount==args.size };method.isAccessible=true
        return method.invoke(a,*args)
    }
    private fun labels(v:View):List<TextView> = (if(v is TextView)listOf(v) else emptyList()) + (if(v is ViewGroup)(0 until v.childCount).flatMap{labels(v.getChildAt(it))} else emptyList())
    @Test fun settingsHaveOneImportAndNoCatalogBackupEntries() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        call(a,"settings")
        val texts=labels(a.window.decorView).map{it.text.toString()}
        assertEquals(1,texts.count{it=="Importa eBook"})
        assertFalse(texts.any{it=="Importa catalogo"||it=="Catalogo e backup"||it=="Esporta backup"||it=="Ripristina o importa un catalogo"})
        controller.pause().stop().destroy()
    }
    @Test fun timerPauseRecreationAndConfirmationCountOnce() {
        var controller=Robolectric.buildActivity(MainActivity::class.java).setup();var a=controller.get()
        val b=Book().apply{id="8".repeat(64);title="Timer test";format="CATALOGO";totalPages=100}
        val field=MainActivity::class.java.getDeclaredField("store").apply{isAccessible=true}
        (field.get(a) as LibraryStore).addCatalog(b)
        call(a,"startTimer",b)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(20))
        labels(a.window.decorView).first{it.text.toString()=="Pausa"}.performClick()
        val seconds=call(a,"timerSeconds") as Long
        ShadowSystemClock.advanceBy(Duration.ofSeconds(50));assertEquals(seconds,call(a,"timerSeconds"))
        controller.recreate();a=controller.get();assertEquals(seconds,call(a,"timerSeconds"))
        val restored=(field.get(a) as LibraryStore).find(b.id)!!
        call(a,"timerScreen",restored)
        labels(a.window.decorView).first{it.text.toString()=="Riprendi"}.performClick()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
        labels(a.window.decorView).first{it.text.toString()=="Termina"}.performClick()
        val save=labels(a.window.decorView).first{it.text.toString()=="Salva la sessione"}
        save.performClick();save.performClick()
        val saved=(field.get(a) as LibraryStore).find(b.id)!!
        assertEquals(1,ReadingJournal.array(saved,"sessions").length())
        assertTrue(ReadingJournal.totals(listOf(saved),null)[1]>=30)
        controller.pause().stop().destroy()
    }
}
