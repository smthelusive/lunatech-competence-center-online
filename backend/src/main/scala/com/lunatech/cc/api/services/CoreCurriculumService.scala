package com.lunatech.cc.api.services

import java.io.{File, FileFilter}

import com.twitter.util.Future
import doobie.imports._
import java.util.UUID

import io.circe._
import io.circe.parser.decode
import io.circe.generic.auto._
import fs2.Task
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger

import scala.io.Source

object CoreCurriculumService {
  case class TopicSummary(id: String,
                          name: String,
                          tags: Set[String])

  case class ProjectSummary(id: String,
                            name: String,
                            description: String)

  case class SubjectSummary(id: String,
                            name: String,
                            description: String,
                            tags: Set[String],
                            topics: Set[TopicSummary],
                            projects: Set[ProjectSummary],
                            image: String,
                            primary: Boolean)

  object SubjectSummary {
    // Manual decoder, to deal with 'primary' being an optional field, defaulting to false.
    implicit val decodeSubjectSummary: Decoder[SubjectSummary] = new Decoder[SubjectSummary] {
      final def apply(c: HCursor): Decoder.Result[SubjectSummary] =
        for {
          id <- c.get[String]("id")
          name <- c.get[String]("name")
          description <- c.get[String]("description")
          tags <- c.get[Set[String]]("tags")
          topics <- c.get[Set[TopicSummary]]("topics")
          projects <- c.getOrElse[Set[ProjectSummary]]("projects")(Set.empty[ProjectSummary])
          image <- c.get[String]("image")
          primary <- c.getOrElse[Boolean]("primary")(false)
        } yield {
          new SubjectSummary(id, name, description, tags, topics, projects, image, primary)
        }
    }

    // Manual decoder, to deal with 'tags' being an optional field, defaulting to empty.
    implicit val decodeTopicSummary: Decoder[TopicSummary] = new Decoder[TopicSummary] {
      final def apply(c: HCursor): Decoder.Result[TopicSummary] =
        for {
          id <- c.get[String]("id")
          name <- c.get[String]("name")
          tags <- c.getOrElse[Set[String]]("tags")(Set.empty[String])
        } yield TopicSummary(id, name, tags)
    }
  }
}

trait CoreCurriculumService {

  import CoreCurriculumService._

  def getSubjectSummaries: Future[Vector[SubjectSummary]]

  def getPersonKnowledge(person: String, subject: String): Future[Vector[String]]
  def getAllPersonKnowledge(person: String): Future[Map[String, Vector[Vector[String]]]]
  def addPersonKnowledge(person: String, subject: String, topic: String): Future[Unit]
  def removePersonKnowledge(person: String, subject: String, topic: String): Future[Unit]
  def getPersonSubjectProjects(person: String, subject: String): Future[Vector[Vector[String]]]
  def getAllPersonProjects(person: String): Future[Map[String, Vector[Vector[String]]]]
  def setProjectInProgress(person: String, subject: String, project: String): Future[Unit]
  def setProjectDone(person: String, subject: String, project: String): Future[Unit]
  def setProjectNotStarted(person: String, subject: String, project: String): Future[Unit]
  def setProjectUrl(person: String, subject: String, project: String, url: Option[String]): Future[Unit]
}

class PostgresCoreCurriculumService(transactor: Transactor[Task], subjectDirectory: File) extends CoreCurriculumService {
  import CoreCurriculumService._

  lazy val logger: Logger = getLogger(getClass)

  logger.info(s"Reading Core Curriculum content from directory [$subjectDirectory]")

  override val getSubjectSummaries: Future[Vector[CoreCurriculumService.SubjectSummary]] = Future {
    val subjectFiles = subjectDirectory.listFiles(new FileFilter {
      override def accept(pathname: File): Boolean = pathname.getName.endsWith(".json")
    })

    subjectFiles.flatMap { file =>
      decode[SubjectSummary](Source.fromFile(file).mkString).fold(
        error => {
          logger.warn("Invalid subject file " + file + ": " + error)
          None
        },
        success => Some(success))
    }.toVector
  }

  override def getPersonKnowledge(person: String, subject: String): Future[Vector[String]] = {

    val query = sql"""
      SELECT topic
      FROM person_knowledge
      WHERE person = ${person}
      AND subject = ${subject}""".query[String]

    Future { query.vector.transact(transactor).unsafeRun }
  }

  override def getAllPersonKnowledge(person: String): Future[Map[String, Vector[Vector[String]]]] = {
    val query = sql"""
      SELECT subject, topic, created_on
      FROM person_knowledge
      WHERE person = ${person}""".query[(String, (String, String))]

    Future {
      query.vector.transact(transactor).unsafeRun
    }.map { pairs => {
      pairs.groupBy(_._1).map { case (s, v) => s -> v.map(_._2).map { case (m,n) => Vector(m, n)} }
    }
    }
  }

  override def addPersonKnowledge(person: String, subject: String, topic: String): Future[Unit] = {

    val id = UUID.randomUUID().toString // TODO, can we stick to UUID here?

    val query = sql"""
      INSERT INTO person_knowledge (id, person, created_on, subject, topic, assessed_on, assessed_by, assessment_notes)
      VALUES ($id :: uuid, ${person}, current_date, $subject, $topic, null, null, null)""".update

    Future { query.run.transact(transactor).unsafeRun }
  }

  override def removePersonKnowledge(person: String, subject: String, topic: String): Future[Unit] = {
    val query = sql"""
                      DELETE FROM person_knowledge
                      WHERE person = $person
                      AND subject = $subject
                      AND topic = $topic""".update

    Future { query.run.transact(transactor).unsafeRun }
  }

  override def getPersonSubjectProjects(person: String, subject: String): Future[Vector[Vector[String]]] = {
    val query = sql"""
      SELECT project, started_on, done_on, url
      FROM person_projects
      WHERE person = ${person}
      AND subject = ${subject}""".query[(String, String, Option[String], Option[String])]

    Future {
      query.vector.transact(transactor).unsafeRun
    }.map { t => {
      t.map { case (s, v, m, r) => Vector(s, v, m.getOrElse("null"), r.getOrElse("empty"))}}
    }
  }

  override def getAllPersonProjects(person: String): Future[Map[String, Vector[Vector[String]]]] = {
    val query = sql"""
      SELECT subject, project, started_on, done_on, url
      FROM person_projects
      WHERE person = ${person}""".query[(String, (String, String, Option[String], Option[String]))]
    Future {
      query.vector.transact(transactor).unsafeRun
    }.map { pairs => {
      pairs.groupBy(_._1).map { case (s, v) => s -> v.map(_._2).map { case (p, so, d, r) =>
        Vector(p, so, d.getOrElse("null"), r.getOrElse("empty")) } }
    }}
  }

  override def setProjectInProgress(person: String, subject: String, project: String): Future[Unit] = {
    val id = UUID.randomUUID().toString
    val query = sql"""
      INSERT INTO person_projects (id, person, started_on, done_on, subject,
      project, url, assessed_on, assessed_by, assessment_notes)
      VALUES ($id :: uuid, ${person}, current_date, null, $subject, $project, null, null, null, null)
      ON CONFLICT (person, subject, project) DO UPDATE
      SET done_on = NULL""".update
    Future { query.run.transact(transactor).unsafeRun }
  }

  override def setProjectDone(person: String, subject: String, project: String): Future[Unit] = {
    val id = UUID.randomUUID().toString
    val query = sql"""
      INSERT INTO person_projects (id, person, started_on, done_on, subject,
      project, url, assessed_on, assessed_by, assessment_notes)
      VALUES ($id :: uuid, ${person}, current_date, current_date, $subject, $project, null, null, null, null)
      ON CONFLICT (person, subject, project) DO UPDATE
      SET done_on = current_date
      WHERE person_projects.done_on is NULL""".update

    Future { query.run.transact(transactor).unsafeRun }
  }

  override def setProjectNotStarted(person: String, subject: String, project: String): Future[Unit] = {
    val query = sql"""
      DELETE FROM person_projects
      WHERE person = $person
      AND subject = $subject
      AND project = $project""".update
    Future { query.run.transact(transactor).unsafeRun }
  }

  override def setProjectUrl(person: String, subject: String, project: String, url: Option[String]): Future[Unit] = {
    val id = UUID.randomUUID().toString
    val query = sql"""
      update person_projects
      set url = ${url.getOrElse("null")}
      where person = $person
      and subject = $subject
      and project = $project""".update
    Future { query.run.transact(transactor).unsafeRun }
  }
}