package app.atlante.library
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import app.atlante.library.model.Book
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A book not in the library opens one full-page sheet; an answer for a book opened before is ignored. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class OnlineSheetTest {
    private fun call(a:MainActivity,name:String,vararg args:Any):Any? {
        val method=MainActivity::class.java.declaredMethods.first { it.name==name && it.parameterCount==args.size };method.isAccessible=true
        return method.invoke(a,*args)
    }
    private fun texts(v:View):List<String> = (if(v is TextView)listOf(v.text.toString()) else emptyList()) + (if(v is ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))} else emptyList())
    private fun field(name:String) = MainActivity::class.java.getDeclaredField(name).apply{isAccessible=true}

    @Test fun lateAnswerForAnotherBookIsIgnored() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        val first=Book().apply{title="Dune";author="Frank Herbert"}
        val second=Book().apply{title="Solaris";author="Stanisław Lem"}
        field("sheetRequest").setInt(a,1); call(a,"onlineSheet",first,true,false,1)
        field("sheetRequest").setInt(a,2); call(a,"onlineSheet",second,true,false,2)
        assertFalse(call(a,"sheetCurrent",1) as Boolean)
        assertTrue(call(a,"sheetCurrent",2) as Boolean)
        assertTrue(texts(a.window.decorView).contains("Solaris"))
        controller.pause().stop().destroy()
    }

    @Test fun noPlotSaysSoAndBackClosesTheSheet() {
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
        val b=Book().apply{title="Libro senza trama";author="Autore"}
        field("sheetRequest").setInt(a,5); call(a,"onlineSheet",b,false,false,5)
        val shown=texts(a.window.decorView)
        assertTrue(shown.contains("Trama non disponibile."))
        assertTrue(shown.contains("Cerca le edizioni online"))
        call(a,"goBack")
        assertNull(field("onlineBook").get(a))
        assertFalse(call(a,"sheetCurrent",5) as Boolean)
        controller.pause().stop().destroy()
    }
}
