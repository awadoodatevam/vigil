// The not-found state of every other path (CTX-D-085 (1)): the DC-U-09 error state inside the signed-in layout.
import { useNavigate } from "react-router";
import { SignedInLayout } from "../layout/SignedInLayout";
import { PageError } from "../states/states";

export function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <SignedInLayout access="view" screen={null} topics={[]} live={false}>
      <PageError reason="Not found." onTryAgain={() => void navigate(0)} />
    </SignedInLayout>
  );
}
