package com.siotman.experimental.mysql.phenomena

import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.headers
import io.kotest.data.row
import io.kotest.data.table
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection

private object DirtyReadEmployeeTable : IntIdTable() {
    val fullName = varchar("full_name", 30)
    val offDays = integer("off_days")
}

private typealias Employees = DirtyReadEmployeeTable

private fun connectionValueOf(intValue: Int) = when (intValue) {
    Connection.TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED"
    Connection.TRANSACTION_READ_COMMITTED -> "READ_COMMITTED"
    Connection.TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ"
    Connection.TRANSACTION_SERIALIZABLE -> "SERIALIZABLE"
    else -> throw Exception("다루지 않거나 정의하지 않은 격리수준입니다.")
}

class DirtyReadTests : StringSpec({
    // Database 설정
    // 두 세션, shinsro 의 세션과 karina 의 세션이 존재함을 가정한다.
    val dbUrl = "jdbc:mysql://localhost:3306/read_phenomena_test"
    val userForShins = "shinsro"
    val userForKarina = "karina"

    val dbSessions = mapOf(
        userForShins to Database.connect(url = dbUrl, user = userForShins),
        userForKarina to Database.connect(url = dbUrl, user = userForKarina)
    )

    // 직원 신스로 정보 초기화 함수
    val shinsRoFullname = "Shins Ro"
    fun Transaction.initShinsRo() {
        Employees.replace { employees ->
            employees[id] = 1
            employees[fullName] = shinsRoFullname
            employees[offDays] = 0
        }
    }

    // 테스트 시나리오 제어를 위한 코루틴 인바운드 이벤트 채널 정의
    class TxDirective(val statement: suspend Transaction.(username: String) -> Unit) {
        suspend fun execute(context: Transaction, username: String) = statement.invoke(context, username)
    }

    val coroutineInBoundChannels = mapOf<String, Channel<TxDirective>>(
        // DB 유저 이름 to 실행할 함수
        userForShins to Channel(),
        userForKarina to Channel()
    )

    val directives = object {
        // 이벤트 행위 1: 신스로의 휴가일수 조회
        val selectShinsRoOffDays = TxDirective { username ->
            val statement = Employees.select(where = { Employees.fullName eq shinsRoFullname })
            val offDays = statement.single()[Employees.offDays]

            println("[$username] $shinsRoFullname 의 휴가일수는 $offDays 입니다.")
        }

        // 이벤트 행위 2: 신스로의 휴가일수 + 1
        val increaseShinsRoOffDaysOne = TxDirective { username ->
            Employees.update(where = { Employees.fullName eq shinsRoFullname }) {
                it.update(Employees.offDays, Employees.offDays + 1)
            }

            println("[$username] $shinsRoFullname 의 휴가일수를 +1 했습니다.")
        }

        // 이벤트 행위 3: commit Tx
        val commit = TxDirective { username ->
            commit()

            println("[$username] 트랜젝션 Commit.")
        }
    }

    // 이벤트 행위 서빙 시작함수
    suspend fun receiveDirectivesUntilCommit(username: String, isolation: Int) {
        val session = dbSessions[username]
        val inbound = coroutineInBoundChannels[username]!!
        println("[$username] is now taking directives.")

        newSuspendedTransaction(db = session, transactionIsolation = isolation) {
            do {
                val directive = inbound.receive()
                directive.execute(this, username)
            } while (directive != directives.commit)
        }
    }

    // === 테스트 케이스 시작 ===
    "Dirty Reads 는 READ_UNCOMMITTED 격리수준에서만 발생한다" {
        forAll(
            table(
                headers("신스로의 격리수준", "카리나의 격리수준"),
                row(Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED),
                row(Connection.TRANSACTION_READ_COMMITTED, Connection.TRANSACTION_READ_COMMITTED),
                row(Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_READ_COMMITTED),
//                InnoDB 의 경우, SERIALIZABLE 에서 모든 select 가 select ... for share 로 치환됩니다.
//                해서 위키의 시나리오가 MySQL innoDB 에서는 어플리케이션 데드락을 발생시키는 바, 결과를 눈으로 확인할 순 없습니다.
//                row(Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_READ_UNCOMMITTED)
            )
        ) { shinsRoIsolation, karinaIsolation ->
            println("신스로의 격리수준이 ${connectionValueOf(shinsRoIsolation)} 일 때는 아래와 같습니다.")

            transaction(statement = Transaction::initShinsRo)

            val shinsRoJob = launch {
                receiveDirectivesUntilCommit(userForShins, shinsRoIsolation)
            }

            val karinaJob = launch {
                receiveDirectivesUntilCommit(userForKarina, karinaIsolation)
            }

            val shinsRoInbound = coroutineInBoundChannels[userForShins]!!
            val karinaInbound = coroutineInBoundChannels[userForKarina]!!

            shinsRoInbound.send(directives.selectShinsRoOffDays)

            delay(1000)
            karinaInbound.send(directives.increaseShinsRoOffDaysOne)

            delay(1000)
            shinsRoInbound.send(directives.selectShinsRoOffDays)

            shinsRoInbound.send(directives.commit)
            karinaInbound.send(directives.commit)

            shinsRoJob.join()
            karinaJob.join()
        }
    }
}) {
    override suspend fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)

        // 디비 Configuration
        TransactionManager.defaultDatabase = Database.connect(
            url = "jdbc:mysql://localhost:3306/read_phenomena_test",
            user = "root"
        )

        // 테스트용 DLL 실행
        transaction {
            SchemaUtils.create(Employees)
        }
    }

    override suspend fun afterSpec(spec: Spec) {
        super.afterSpec(spec)

        // 테스트용 DLL 드롭
        transaction {
            SchemaUtils.drop(Employees)
        }
    }
}