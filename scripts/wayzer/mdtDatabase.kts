@file:Depends("coreLibrary/db", "数据库储存")

package wayzer

import coreLib.db.DBApi
import wayzer.lib.MdtStorage

DBApi.registerTable(*MdtStorage.tables())
