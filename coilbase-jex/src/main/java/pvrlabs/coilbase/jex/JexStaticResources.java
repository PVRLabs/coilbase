package pvrlabs.coilbase.jex;

import io.avaje.jex.Jex;
import io.avaje.jex.staticcontent.StaticContent;

/** Isolates static-content initialization behind the application opt-in. */
final class JexStaticResources {
    private JexStaticResources() {}

    static void install(Jex jex) {
        jex.plugin(StaticContent.ofClassPath("/static/").directoryIndex("index.html").build());
    }
}
