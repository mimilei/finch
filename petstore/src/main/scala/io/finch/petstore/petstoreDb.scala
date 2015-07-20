package io.finch.petstore

import com.twitter.util.{Await, Future}

import scala.collection.mutable

/**
 * Provides a great majority of the service methods that allow Users to interact with the Pets in the
 * store and to get information about them.
 */
class PetstoreDb {
  private[this] val pets = mutable.Map.empty[Long, Pet]
  private[this] val tags = mutable.Map.empty[Long, Tag]
  private[this] val cat = mutable.Map.empty[Long, Category]
  private[this] val orders = mutable.Map.empty[Long, Order]
  private[this] val photos = mutable.Map.empty[Long, Array[Byte]]
  private[this] val users = mutable.Map.empty[Long, User]

  /**
   * GET: Finds a [[Pet]] by its ID.
   *
   * @param id The ID of the [[Pet]] we're looking for
   * @return The [[Pet]] object
   */
  def getPet(id: Long): Future[Pet] = Future(
    pets.synchronized {
      pets.getOrElse(id, throw MissingPet("Your pet doesn't exist! :("))
    }
  )

  /**
   * Helper method for addPet: Adds a [[Tag]] to the tag map.
   * This differs from the Swagger example in that the Tag's ID is autogenerated
   * rather than passed by the user. This is done to avoid potential memory and storage
   * sabotage. Tags with given IDs will be rejected.
   * @param inputTag The tag we want to add
   * @return The tag just added
   */
  private def addTag(inputTag: Tag): Future[Tag] =
    tags.synchronized {
      inputTag.id match {
        case Some(x) => Future.exception(InvalidInput("New tag should not contain an id"))
        case _ => tags.synchronized {
          val genId = if (tags.isEmpty) 0 else tags.keys.max + 1
          tags(genId) = inputTag.copy(id = Some(genId))
          Future(tags(genId))
        }
      }
    }

  /**
   * Helper method for addPet: Adds a [[Category]] to the category map.
   * This differs from the Swagger example in that the Category's ID is autogenerated
   * rather than passed by the user. This is done to avoid potential memory and storage
   * sabotage. Category with given IDs will be rejected.
   * @param c The Category we want to add
   * @return The Category just added
   */
  private def addCat(c: Category): Future[Category] =
    cat.synchronized {
      c.id match{
        case Some(x) => Future.exception(InvalidInput("New Category should not contain an id"))
        case None => cat.synchronized{
          val genId = if (cat.isEmpty) 0 else cat.keys.max + 1
          cat(genId) = c.copy(id = Some(genId))
          Future(cat(genId))
        }
      }
    }

  /**
   * POST: Adds a [[Pet]] to the database, validating that the ID is empty.
   *
   * @param inputPet the new pet
   * @return the id of the new pet
   */
  def addPet(inputPet: Pet): Future[Long] =
    inputPet.id match {
      case Some(_) => Future.exception(InvalidInput("New pet should not contain an id"))
      case None => pets.synchronized {
        val id = if (pets.isEmpty) 0 else pets.keys.max + 1
        pets(id) = inputPet.copy(id = Some(id))

        inputPet.tags match{
          case Some(tagList) => tagList.map(addTag)
          case None => None
        }

        inputPet.category match{
          case Some(c) => addCat(c)
          case None => None
        }

        Future.value(id)
      }
    }

  /**
   * PUT: Updates an existing [[Pet]], while validating that a current version of
   * the [[Pet]] exists (a.k.a. an existing [[Pet]] has the same id as inputPet).
   * @param inputPet The [[Pet]] we want to replace the current [[Pet]] with. Must be passed with the original Pet's ID.
   * @return The updated pet
   */
  def updatePet(inputPet: Pet): Future[Pet] = inputPet.id match {
    case Some(id) =>
      if(pets.contains(id)) pets.synchronized{
        pets(id) = inputPet
        Future.value(inputPet)
      } else {
        Future.exception(MissingPet("Invalid id: doesn't exist"))
      }
    case None => Future.exception(MissingIdentifier(s"Missing id for pet: $inputPet"))
  }

  /**
   * Helper method: Allows the user to get all the pets in the database.
   * @return A sequence of all pets in the store.
   */
  private def allPets: Future[Seq[Pet]] = Future.value(
    pets.synchronized(pets.toList.sortBy(_._1).map(_._2))
  )

  /**
   * GET: Find pets by status. Multiple statuses can be provided with comma-separated strings.
   * @param findStati The status(es) to filter Pets by.
   * @return A sequence of all Pets with the given status(es).
   */
  def getPetsByStatus(findStati: Seq[String]): Future[Seq[Pet]] = {
    pets.synchronized(
      for {
        petList <- allPets
      } yield petList.filter(p => p.status.exists(c => findStati.contains(c.code)))
    )
  }

  /**
   * GET: Find pets by [[Tag]]. Multiple tags can be provided with comma-separated strings.
   * @param findTags A sequence of all the Tags we want to find matches for.
   * @return A sequence of Pets that contain all given Tags.
   */
  def findPetsByTag(findTags: Seq[String]): Future[Seq[Pet]] = {
    val matchPets = for {
      p <- pets.values
      tagList <- p.tags
      pTagStr = tagList.map(_.name)
      if findTags.forall(pTagStr.contains)
    } yield p
    Future(matchPets.toSeq.sortBy(_.id))
  }

  /**
   * DELETE: Deletes a [[Pet]] from the database.
   * @param id The ID of the Pet to be deleted.
   * @return true if deletion was successful. false otherwise.
   */
  def deletePet(id: Long): Future[Unit] =
    pets.synchronized {
      if (pets.contains(id)) {
        pets.remove(id)
        Future.Unit
      } else Future.exception(
        MissingPet(s"Pet with id $id does not exist and cannot be deleted")
      )
    }

  /**
   * POST: Update a [[Pet]] in the store with form data
   * @param petId ID of the Pet to be updated.
   * @param n New name of the Pet.
   * @param s New status of the Pet.
   * @return The updated Pet.
   */
  def updatePetViaForm(petId: Long, n: Option[String], s: Option[Status]): Future[Pet] = {
      if (pets.contains(petId)) pets.synchronized{
        s.foreach { stat => pets(petId) = pets(petId).copy(status = Some(stat))}
        n.foreach { name => pets(petId) = pets(petId).copy(name = name) }
        Future.value(pets(petId))
      } else Future.exception(MissingPet("Invalid id: doesn't exist"))
    }

  /**
   * POST: Upload an image.
   * @param petId The ID of the pet the image corresponds to.
   * @param data The image in byte form.
   * @return The url of the uploaded photo.
   */
  def addImage(petId: Long, data: Array[Byte]): Future[String] =
    pets.synchronized {
      for {
        pet <- getPet(petId)
        photoId = photos.synchronized {
          val nextId = if (photos.isEmpty) 0 else photos.keys.max + 1
          photos(nextId) = data
          nextId
        }
        url = s"/photos/$photoId"
        _ <- updatePet(pet.copy(photoUrls = pet.photoUrls :+ url))
      } yield url
    }

  /**
   * GET: Returns the current [[Inventory]].
   * @return A map of how many pets currently correspond to which Status type.
   */
  def getInventory: Future[Inventory] = Future.value(
    pets.synchronized {
      val stock: Map[Status, Int] = pets.groupBy(_._2.status).flatMap {
        case (Some(status), keyVal) => Some(status -> keyVal.size)
        case (None, _) => None
      }
      val available: Int = stock(Available)
      val pending: Int = stock(Pending)
      val adopted: Int = stock(Adopted)
      Inventory(available, pending, adopted)
    }
  )

  /**
   * POST: Place an [[Order]] for a [[Pet]].
   * @param order The order object to be placed with the petstore.
   * @return The autogenerated ID of the order object.
   */
  def addOrder(order: Order): Future[Long] =
    orders.synchronized {
      order.id match{
        case Some(_) => Future.exception(InvalidInput("New order should not contain an id"))
        case None => orders.synchronized{
          val genId = if (orders.isEmpty) 0 else orders.keys.max + 1
          orders(genId) = order.copy(id = Some(genId))
          Future.value(genId)
        }
      }
    }

  /**
   * DELETE: Delete purchase [[Order]] by ID
   * @param id The ID of the order to delete.
   * @return true if deletion was successful. false otherwise.
   */
  def deleteOrder(id: Long): Future[Boolean] = Future.value(
    orders.synchronized {
      if (orders.contains(id)) {
        orders.remove(id)
        true
      } else false
    }
  )

  /**
   * GET: Find purchase [[Order]] by ID
   * @param id The ID of the order to find.
   * @return The Order object in question.
   */
  def findOrder(id: Long): Future[Order] = Future.value(
    orders.synchronized {
      orders.getOrElse(id, throw OrderNotFound("Your order doesn't exist! :("))
    }
  )

  /**
   * POST: Create a [[User]].
   * @param newGuy The User we want to add to the database.
   * @return The user name of the added User.
   */
  def addUser(newGuy: User): Future[String] =
    users.synchronized {
      val inputName: String = newGuy.username
      if (users.values.exists(_.username == inputName))
        throw RedundantUsername(s"Username $inputName is already taken.")
      else {
        newGuy.id match {
          case Some(_) => Future.exception(InvalidInput("New user should not contain an id"))
          case None => users.synchronized {
            val genId = if (users.isEmpty) 0 else users.keys.max + 1
            users(genId) = newGuy.copy(id = Some(genId))
            Future(newGuy.username)
          }
        }
      }
    }

  /**
   * GET: Get [[User]] by username, assume all usernames are unique.
   * @param name The username of the User we want to find.
   * @return The User in question.
   */
  def getUser(name: String): Future[User] =
    users.synchronized {
      users.values.find(_.username == name) match {
        case Some(user) => Future.value(user)
        case None => Future.exception(MissingUser("This user doesn't exist!"))
      }
    }

  /**
   * DELETE: Delete a [[User]] by their username.
   * @param name The username of the User to be deleted.
   */
  def deleteUser(name: String): Future[Unit] =
    users.synchronized {
      getUser(name).flatMap {u:User =>
        u.id.foreach{ num =>
          users.remove(num)
        }
        Future.Unit
      }
    }

  /**
   * PUT: Update [[User]]. Note that usernames cannot be changed because they are the unique identifiers by which the
   * system finds existing users. Although Swagger doesn't specify this, if the username of an existing user is
   * changed, the API will no longer be able to find the user or the user's unique id.
   * @param betterUser The better, updated version of the old User.
   * @return The betterUser.
   */
  def updateUser(betterUser: User): Future[User] =
    users.synchronized {
      for {
        user <- getUser(betterUser.username)
        u = betterUser.copy(id = user.id)
      } yield {
        u.id.foreach { id =>
          users(id) = u
        }
        u
      }
    }
}

