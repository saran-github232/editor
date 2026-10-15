package app.clipper

import android.app.Application
import app.clipper.data.ProjectStore

class ClipperApp : Application() {
    lateinit var store: ProjectStore
        private set
    override fun onCreate() { super.onCreate(); store = ProjectStore(this) }
}
