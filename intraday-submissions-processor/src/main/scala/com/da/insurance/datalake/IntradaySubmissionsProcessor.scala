package com.da.insurance.datalake

import org.apache.hudi.DataSourceWriteOptions
import org.apache.hudi.config.HoodieWriteConfig
import org.apache.spark.sql.functions.{col, current_timestamp, lit, regexp_replace, to_date}
import org.apache.spark.sql.types.{DataType, IntegerType, StringType, StructType}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

import scala.util.Try

object IntradaySubmissionsProcessor {

  // hudi config
  val hudiDbName = "ins_data_lake_transformed"
  val hudiTableName = "hudi_submissions"
  val hudiTableRecordKey = "submission_id"
  val hudiTablePartitionColumn = "submitted_on"
  val hudiTablePrecombineKey = "timestamp"
  var hudiSyncToHive = true

  def main(args: Array[String]): Unit = {

    val pathToSource: String = args(0)
    val pathToDestination: String = args(1)

    var isLocalRun: Boolean = false
    if (args.length > 2) isLocalRun = Try(args(2).toBoolean).getOrElse(false)
    hudiSyncToHive = !isLocalRun

    var spark: SparkSession = null
    try {

      var sessionBuilder = SparkSession
        .builder()
        .appName("IntradaySubmissionsProcessor")
        .config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        .config("spark.sql.hive.convertMetastoreParquet", "false")

      if (isLocalRun)
        sessionBuilder = sessionBuilder
          .master("local[*]")
          .config("spark.driver.host", "localhost")

      spark =  sessionBuilder.getOrCreate()

      val sourceData = spark
        .read
        .format("xml")
        .option("rootTag", "Submission")
        .option("rowTag", "Submission")
        .load(pathToSource)
        .withColumn("SubmissionDate", regexp_replace(to_date(col("SubmissionDate"), "MM/dd/yyyy"), "-", "/"))
      //        .cache()

      // get source values
      val row = sourceData
        .select("SubmissionID", "SubmissionStatus", "SubmissionDate", "City", "Company", "State", "Street",
          "Zip", "Underwriter")
        .first()
      val subId = row(0)
      val subStatus = row(1)
      val subDate = row(2)
      val city = row(3)
      val organization = row(4)
      val state = row(5)
      val street = row(6)
      val zip = row(7)
      val underwriter = row(8)

      // get existing hudi data
      val hudiDf = spark
        .read
        .format("org.apache.hudi")
        .load(pathToDestination + "/*/*/*/*")

      var upsertDf = hudiDf
        .where("submission_id = " + subId)
        .withColumn("submission_status", lit(subStatus).cast(StringType))
        .withColumn("submitted_on", lit(subDate).cast(StringType))
        .withColumn("city", lit(city).cast(StringType))
        .withColumn("organization", lit(organization).cast(StringType))
        .withColumn("state", lit(state).cast(StringType))
        .withColumn("street", lit(street).cast(StringType))
        .withColumn("zip", lit(zip).cast(IntegerType))
        .withColumn("underwriter", lit(underwriter).cast(StringType))

      if (sourceData.columns.contains("YearBuilt")) {
        val yearBuilt = sourceData.select("YearBuilt").first()(0)
        upsertDf = upsertDf.withColumn("year_built", lit(yearBuilt).cast(IntegerType))
      }

      if (sourceData.columns.contains("BuildingValue")) {
        val buildingValue = sourceData.select("BuildingValue").first()(0)
        upsertDf = upsertDf.withColumn("building_value", lit(buildingValue).cast(IntegerType))
      }

      saveToHudi(upsertDf, pathToDestination)

    }
    finally {
      if (spark != null) {
        spark.stop()
      }
    }
  }

  def saveToHudi(df: DataFrame, pathToDestination: String): Unit = {
    val hudiOptions = Map[String, String](
      HoodieWriteConfig.TABLE_NAME -> hudiTableName,

      //For this data set, we will configure it to use the COPY_ON_WRITE storage strategy.
      //You can also choose MERGE_ON_READ
      DataSourceWriteOptions.STORAGE_TYPE_OPT_KEY -> "COPY_ON_WRITE",

      //These three options configure what Hudi should use as its record key,
      //partition column, and precombine key.
      DataSourceWriteOptions.RECORDKEY_FIELD_OPT_KEY -> hudiTableRecordKey,
      DataSourceWriteOptions.PRECOMBINE_FIELD_OPT_KEY -> hudiTablePrecombineKey,
      DataSourceWriteOptions.PARTITIONPATH_FIELD_OPT_KEY -> hudiTablePartitionColumn,

      //For this data set, we specify that we want to sync metadata with Hive.
      DataSourceWriteOptions.HIVE_SYNC_ENABLED_OPT_KEY -> hudiSyncToHive.toString,
      DataSourceWriteOptions.HIVE_DATABASE_OPT_KEY -> hudiDbName,
      DataSourceWriteOptions.HIVE_TABLE_OPT_KEY -> hudiTableName,
      DataSourceWriteOptions.HIVE_PARTITION_FIELDS_OPT_KEY -> hudiTablePartitionColumn
    )

    //write to Hudi
    df.write
      .format("org.apache.hudi")
      .options(hudiOptions)
      //Operation Key tells Hudi whether this is an Insert, Upsert, or Bulk Insert operation
      //INSERT_OPERATION_OPT_VAL //BULK_INSERT_OPERATION_OPT_VAL //UPSERT_OPERATION_OPT_VAL - read abt different options. e.g. can i use upsert all the time? even the first time?
      .option(DataSourceWriteOptions.OPERATION_OPT_KEY, DataSourceWriteOptions.UPSERT_OPERATION_OPT_VAL)
      .mode(SaveMode.Append)
      .save(pathToDestination + "/" + hudiTableName)
  }
}
