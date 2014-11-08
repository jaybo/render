package org.janelia.alignment.spec;

import mpicbg.models.CoordinateTransform;
import mpicbg.models.InterpolatedCoordinateTransform;

import java.util.Map;
import java.util.Set;

/**
 * Specification for an {@link InterpolatedCoordinateTransform}.
 *
 * NOTE: The {@link org.janelia.alignment.json.TransformSpecAdapter} implementation handles
 * polymorphic deserialization for this class and is tightly coupled to the implementation here.
 * The adapter will need to be modified any time attributes of this class are modified.

 * @author Eric Trautman
 */
public class InterpolatedTransformSpec
        extends TransformSpec {

    public static final String TYPE = "interpolated";

    public static final String A_ELEMENT_NAME = "a";
    public static final String B_ELEMENT_NAME = "b";
    public static final String LAMBDA_ELEMENT_NAME = "lambda";

    private TransformSpec a;
    private TransformSpec b;
    private Float lambda;

    public InterpolatedTransformSpec(String id,
                                     TransformSpecMetaData metaData,
                                     TransformSpec a,
                                     TransformSpec b,
                                     Float lambda) {
        super(id, TYPE, metaData);
        this.a = a;
        this.b = b;
        this.lambda = lambda;
    }

    @Override
    public boolean isFullyResolved()
            throws IllegalStateException {
        return (a.isFullyResolved() && b.isFullyResolved());
    }

    @Override
    public void addUnresolvedIds(Set<String> unresolvedIds) {
        a.addUnresolvedIds(unresolvedIds);
        b.addUnresolvedIds(unresolvedIds);
    }

    @Override
    public void resolveReferences(Map<String, TransformSpec> idToSpecMap) {
        a.resolveReferences(idToSpecMap);
        b.resolveReferences(idToSpecMap);
    }

    @Override
    public void flatten(ListTransformSpec flattenedList)
            throws IllegalStateException {

        flattenedList.addSpec(new InterpolatedTransformSpec(getId(),
                                                            getMetaData(),
                                                            getFlattenedComponentSpec(a),
                                                            getFlattenedComponentSpec(b),
                                                            lambda));
    }

    @Override
    protected CoordinateTransform buildInstance()
            throws IllegalArgumentException {
        return new InterpolatedCoordinateTransform<CoordinateTransform, CoordinateTransform>(a.buildInstance(),
                                                                                             b.buildInstance(),
                                                                                             lambda);
    }

    private TransformSpec getFlattenedComponentSpec(TransformSpec spec)
            throws IllegalStateException {

        final ListTransformSpec flattenedList = new ListTransformSpec();
        spec.flatten(flattenedList);
        TransformSpec flattenedSpec = flattenedList;
        if (flattenedList.size() == 1) {
            flattenedSpec = flattenedList.getSpec(0);
        }
        return flattenedSpec;
    }
}
