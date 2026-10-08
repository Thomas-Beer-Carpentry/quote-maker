package nz.co.timbertakeoff

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import nz.co.timbertakeoff.data.EstimatorDatabase
import nz.co.timbertakeoff.data.EstimatorRepository

class EstimatorApplication : Application() {
    /** Accepted local writes finish even when the last Activity is closed. */
    internal val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val database by lazy { EstimatorDatabase.open(this) }
    val repository by lazy { EstimatorRepository(database.estimatorDao()) }
}
