/*
 * Copyright 2019 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.vatapi.resources

import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc.{Action, AnyContent}
import uk.gov.hmrc.domain.Vrn
import uk.gov.hmrc.vatapi.audit.AuditEvents
import uk.gov.hmrc.vatapi.config.AppContext
import uk.gov.hmrc.vatapi.connectors.VatReturnsConnector
import uk.gov.hmrc.vatapi.models.audit.AuditResponse
import uk.gov.hmrc.vatapi.models.des.VatReturnsDES
import uk.gov.hmrc.vatapi.models.{Errors, VatReturnDeclaration}
import uk.gov.hmrc.vatapi.orchestrators.VatReturnsOrchestrator
import uk.gov.hmrc.vatapi.resources.wrappers.Response
import uk.gov.hmrc.vatapi.services.{AuditService, AuthorisationService}
import v2.models.audit.AuditError

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class VatReturnsResource @Inject()(
                                    connector: VatReturnsConnector,
                                    orchestrator: VatReturnsOrchestrator,
                                    override val authService: AuthorisationService,
                                    override val appContext: AppContext,
                                    auditService: AuditService
                                  ) extends BaseResource {

  def submitVatReturn(vrn: Vrn): Action[JsValue] = APIAction(vrn, nrsRequired = true).async(parse.json) { implicit request =>
    val receiptId = "Receipt-ID"
    val receiptTimestamp = "Receipt-Timestamp"
    val receiptSignature = "Receipt-Signature"

    logger.debug(s"[VatReturnsResource][submitVatReturn] - Submitting Vat Return")
    val result = fromDes {
      for {
        vatReturn <- validateJson[VatReturnDeclaration](request.body)
        _ <- authorise(vatReturn) { case _ if !vatReturn.finalised => Errors.NotFinalisedDeclaration }
        response <- BusinessResult {
          orchestrator.submitVatReturn(vrn, vatReturn)
        }
      } yield response
    } onSuccess { response =>
      response.filter {
        case 200 => response.vatSubmissionReturnOrError match {
          case Right(vatReturnDesResponse) =>
            def successResponse(vatReturn: VatReturnsDES) = {
              logger.debug(s"[VatReturnsResource][submitVatReturn] - Successfully created ")
              Created(Json.toJson(vatReturn)).withHeaders(
                receiptId -> response.nrsData.nrSubmissionId,
                receiptTimestamp -> response.nrsData.timestamp,
                receiptSignature -> response.nrsData.cadesTSignature)
            }
            vatReturnDesResponse.validate[VatReturnsDES] match {
              case JsSuccess(vatReturn, _) => successResponse(vatReturn)
              case JsError(errs) =>
                logger.warn(s"[VatReturnsResource] [submitVatReturn] Could not read response from DES as a Vat Return $errs")
                InternalServerError(Json.toJson(Errors.InternalServerError))
            }
        }
      }
    }
    result.recover {
      case ex =>
        logger.warn(s"[VatReturnsResource] [submitVatReturn] Unexpected downstream error thrown ${ex.getMessage}")
        InternalServerError(Json.toJson(Errors.InternalServerError))
    }
  }

  def retrieveVatReturns(vrn: Vrn, periodKey: String): Action[AnyContent] =
    APIAction(vrn).async { implicit request =>

      def audit(connectorResponse: Option[Response], auditResponse: AuditResponse) = {
        auditService.audit(AuditEvents.retrieveVatReturnsAudit(Response.getCorrelationId(connectorResponse),
          request.authContext.affinityGroup, getArn, auditResponse))
      }

      logger.debug(s"[VatReturnsResource] [retrieveVatReturns] Retrieve VAT returns for VRN : $vrn")
      val result = fromDes {
        for {
          _ <- validate[String](periodKey) { case _ if periodKey.length != 4 => Errors.InvalidPeriodKey }
          response <- execute { _ => connector.query(vrn, periodKey) }
        } yield response
      } onSuccess { connectorResponse =>
        connectorResponse.filter {
          case OK => connectorResponse.vatReturnOrError match {
            case Right(vatReturn) =>
              val responseBody = Json.toJson(vatReturn)
              audit(Some(connectorResponse), AuditResponse(OK, None, Some(responseBody)))
              Ok(responseBody)
            case Left(error) =>
              logger.error(s"[VatReturnsResource] [retrieveVatReturns] Json format from DES doesn't match the VatReturn model: ${error.msg}")
              audit(None, AuditResponse(INTERNAL_SERVER_ERROR, Some(Seq(AuditError(Errors.InternalServerError.code))), None))
              InternalServerError
          }
        }
      }

      result.recover {
        case ex =>
          logger.warn(s"[VatReturnsResource][retrieveVatReturns] Unexpected downstream error thrown ${ex.getMessage}")
          audit(None, AuditResponse(INTERNAL_SERVER_ERROR, Some(Seq(AuditError(Errors.InternalServerError.code))), None))
          InternalServerError(Json.toJson(Errors.InternalServerError))
      }
    }

  private case class RetrieveVatReturn(vrn: Vrn, httpStatus: Int, responsePayload: JsValue)

  private implicit val retrieveVatReturnFormat: OFormat[RetrieveVatReturn] = Json.format[RetrieveVatReturn]

}
