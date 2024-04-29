import { Connection, getIncomers, Node, NodeAddChange, XYPosition } from 'reactflow'

import { DataPolicy, DataPolicyValidator, Schema, SchemaReference } from '@/api/__generated__'
import { enumFromStringValue } from '@/utils/types.utils.ts'

import i18n from '@/config/i18n.config.ts'

import {
  DataHubNodeType,
  DataPolicyData,
  DryRunResults,
  ResourceStatus,
  SchemaArguments,
  ValidatorData,
  ValidatorType,
  WorkspaceState,
} from '@datahub/types.ts'
import { checkValiditySchema, loadSchema } from '@datahub/designer/schema/SchemaNode.utils.ts'
import { PolicyCheckErrors } from '@datahub/designer/validation.errors.ts'
import { getNodeId, isSchemaNodeType, isValidatorNodeType } from '@datahub/utils/node.utils.ts'
import { CANVAS_POSITION } from '@datahub/designer/checks.utils.ts'

export function checkValidityPolicyValidator(
  validator: Node<ValidatorData>,
  store: WorkspaceState
): DryRunResults<DataPolicyValidator, Schema> {
  const { nodes, edges } = store

  const schemas = getIncomers(validator, nodes, edges).filter(isSchemaNodeType)

  if (!schemas.length) {
    return {
      node: validator,
      error: PolicyCheckErrors.notConnected(DataHubNodeType.SCHEMA, validator),
    }
  }

  const schemaNodes = schemas.map((e) => checkValiditySchema(e))
  const operation: DataPolicyValidator = {
    type: validator.data.type,
    // TODO[NVL] Arguments is not typed on the backend!
    arguments: {
      schemas: schemas.map<SchemaReference>((schema) => {
        const version =
          schema.data.version === ResourceStatus.DRAFT || schema.data.version === ResourceStatus.MODIFIED
            ? 'latest'
            : schema.data.version.toString()
        return { schemaId: schema.data.name, version }
      }),
      strategy: validator.data.strategy,
    } as SchemaArguments,
  }
  return { data: operation, node: validator, resources: [...schemaNodes] }
}

export function checkValidityPolicyValidators(
  dataPolicyNode: Node<DataPolicyData>,
  store: WorkspaceState
): DryRunResults<DataPolicyValidator>[] {
  const { nodes, edges } = store

  const incomers = getIncomers(dataPolicyNode, nodes, edges).filter(isValidatorNodeType)

  return incomers.map((validator) => checkValidityPolicyValidator(validator, store))
}

export const loadValidators = (policy: DataPolicy, schemas: Schema[], dataPolicyNode: Node<DataPolicyData>) => {
  if (dataPolicyNode.id !== policy.id)
    throw new Error(
      i18n.t('datahub:error.loading.connection.notFound', { type: DataHubNodeType.DATA_POLICY }) as string
    )

  const position: XYPosition = {
    x: dataPolicyNode.position.x + CANVAS_POSITION.Validator.x,
    y: dataPolicyNode.position.y + CANVAS_POSITION.Validator.y,
  }

  const newNodes: (NodeAddChange | Connection)[] = []
  for (const validator of policy.validation?.validators || []) {
    const validatorArguments = validator.arguments as SchemaArguments

    const validatorNode: Node<ValidatorData> = {
      id: getNodeId(),
      type: DataHubNodeType.VALIDATOR,
      position,
      data: {
        strategy: validatorArguments.strategy,
        // @ts-ignore force undefined
        type: enumFromStringValue(ValidatorType, validator.type),
        schemas: validatorArguments.schemas,
      },
    }

    newNodes.push({ item: validatorNode, type: 'add' })
    newNodes.push({
      source: validatorNode.id,
      target: dataPolicyNode.id,
      sourceHandle: null,
      targetHandle: DataPolicyData.Handle.VALIDATION,
    })

    for (const schemaRef of validatorArguments.schemas) {
      const schemaNodes = loadSchema(validatorNode, null, 0, schemaRef, schemas)
      newNodes.push(...schemaNodes)
    }
  }

  return newNodes
}
