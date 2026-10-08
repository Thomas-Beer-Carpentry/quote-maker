package nz.co.timbertakeoff

import android.app.Application
import nz.co.timbertakeoff.data.EstimatorDatabase
import nz.co.timbertakeoff.data.EstimatorRepository

class EstimatorApplication : Application() {
    private val database by lazy { EstimatorDatabase.open(this) }
    val repository by lazy { EstimatorRepository(database.estimatorDao()) }
}
