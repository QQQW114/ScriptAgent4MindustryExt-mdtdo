@file:Depends("coreLibrary/DBApi", "数据库储存")

package wayzer

import coreLibrary.DBApi.DB.registerTable
import wayzer.lib.MdtStorage

registerTable(*MdtStorage.tables())
